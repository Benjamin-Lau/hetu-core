/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"inputPage", "unfinishedWork", "snapshotState"})
public class MarkDistinctOperator
        implements Operator
{
    public static class MarkDistinctOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Optional<Integer> hashChannel;
        private final List<Integer> markDistinctChannels;
        private final List<Type> types;
        private final JoinCompiler joinCompiler;
        private boolean closed;

        public MarkDistinctOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                List<? extends Type> sourceTypes,
                Collection<Integer> markDistinctChannels,
                Optional<Integer> hashChannel,
                JoinCompiler joinCompiler)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.markDistinctChannels = ImmutableList.copyOf(requireNonNull(markDistinctChannels, "markDistinctChannels is null"));
            checkArgument(!markDistinctChannels.isEmpty(), "markDistinctChannels is empty");
            this.hashChannel = requireNonNull(hashChannel, "hashChannel is null");
            this.joinCompiler = requireNonNull(joinCompiler, "joinCompiler is null");
            this.types = ImmutableList.<Type>builder()
                    .addAll(sourceTypes)
                    .add(BOOLEAN)
                    .build();
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, MarkDistinctOperator.class.getSimpleName());
            return new MarkDistinctOperator(operatorContext, types, markDistinctChannels, hashChannel, joinCompiler);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new MarkDistinctOperatorFactory(operatorId, planNodeId, types.subList(0, types.size() - 1), markDistinctChannels, hashChannel, joinCompiler);
        }
    }

    private final OperatorContext operatorContext;
    private final MarkDistinctHash markDistinctHash;
    private final LocalMemoryContext localUserMemoryContext;

    private Page inputPage;
    private boolean finishing;

    // for yield when memory is not available
    private Work<Block> unfinishedWork;

    private final SingleInputSnapshotState snapshotState;

    public MarkDistinctOperator(OperatorContext operatorContext, List<Type> types, List<Integer> markDistinctChannels, Optional<Integer> hashChannel, JoinCompiler joinCompiler)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");

        requireNonNull(hashChannel, "hashChannel is null");
        requireNonNull(markDistinctChannels, "markDistinctChannels is null");

        ImmutableList.Builder<Type> distinctTypes = ImmutableList.builder();
        for (int channel : markDistinctChannels) {
            distinctTypes.add(types.get(channel));
        }
        this.markDistinctHash = new MarkDistinctHash(operatorContext.getSession(), distinctTypes.build(), Ints.toArray(markDistinctChannels), hashChannel, joinCompiler, this::updateMemoryReservation);
        this.localUserMemoryContext = operatorContext.localUserMemoryContext();
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, operatorContext) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        return finishing && !hasUnfinishedInput();
    }

    @Override
    public boolean needsInput()
    {
        return !finishing && !hasUnfinishedInput();
    }

    @Override
    public void addInput(Page page)
    {
        requireNonNull(page, "page is null");
        checkState(needsInput());

        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }

        inputPage = page;

        unfinishedWork = markDistinctHash.markDistinctRows(page);
        updateMemoryReservation();
    }

    @Override
    public Page getOutput()
    {
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        if (unfinishedWork == null) {
            return null;
        }

        if (!unfinishedWork.process()) {
            return null;
        }

        // add the new boolean column to the page
        Page outputPage = inputPage.appendColumn(unfinishedWork.getResult());

        unfinishedWork = null;
        inputPage = null;

        updateMemoryReservation();
        return outputPage;
    }

    @Override
    public Page pollMarker()
    {
        return snapshotState.nextMarker();
    }

    private boolean hasUnfinishedInput()
    {
        return inputPage != null || unfinishedWork != null;
    }

    /**
     * Update memory usage.
     *
     * @return true to if the reservation is within the limit
     */
    // TODO: update in the interface now that the new memory tracking framework is landed
    // Essentially we would love to have clean interfaces to support both pushing and pulling memory usage
    // The following implementation is a hybrid model, where the push model is going to call the pull model causing reentrancy
    private boolean updateMemoryReservation()
    {
        // Operator/driver will be blocked on memory after we call localUserMemoryContext.setBytes().
        // If memory is not available, once we return, this operator will be blocked until memory is available.
        localUserMemoryContext.setBytes(markDistinctHash.getEstimatedSize());
        // If memory is not available, inform the caller that we cannot proceed for allocation.
        return operatorContext.isWaitingForMemory().isDone();
    }

    @VisibleForTesting
    public int getCapacity()
    {
        return markDistinctHash.getCapacity();
    }

    @Override
    public void close()
    {
        if (snapshotState != null) {
            snapshotState.close();
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        MarkDistinctOperatorState myState = new MarkDistinctOperatorState();
        myState.operatorContext = operatorContext.capture(serdeProvider);
        myState.markDistinctHash = markDistinctHash.capture(serdeProvider);
        myState.localUserMemoryContext = localUserMemoryContext.getBytes();
        myState.finishing = finishing;
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        MarkDistinctOperatorState myState = (MarkDistinctOperatorState) state;
        this.operatorContext.restore(myState.operatorContext, serdeProvider);
        this.markDistinctHash.restore(myState.markDistinctHash, serdeProvider);
        this.localUserMemoryContext.setBytes(myState.localUserMemoryContext);
        this.finishing = myState.finishing;
    }

    private static class MarkDistinctOperatorState
            implements Serializable
    {
        private Object operatorContext;
        private Object markDistinctHash;
        private long localUserMemoryContext;
        private boolean finishing;
    }
}

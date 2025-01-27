package io.metaloom.graph.core.internal.rel;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import io.metaloom.graph.core.internal.AbstractMMapFileStorage;
import io.metaloom.graph.core.internal.FileHeader;

public class NodeRelationshipStorageImpl extends AbstractMMapFileStorage implements NodeRelationshipStorage {

	private static final String HEADER_FILE_TYPE = "nore";

	private static final String FREE_KEY = "free";

	private static final String NODE_OFFSET_KEY = "node_offset";

	private static final String REL_OFFSET_KEY = "rel_offset";

	private static final String NEXT_OFFSET_KEY = "next";

	private static final GroupLayout LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_LONG.withName(NODE_OFFSET_KEY),
		ValueLayout.JAVA_LONG.withName(NEXT_OFFSET_KEY),
		ValueLayout.JAVA_BOOLEAN.withName(FREE_KEY),
		MemoryLayout.paddingLayout(7),
		ValueLayout.JAVA_LONG.withName(REL_OFFSET_KEY));

	private AtomicLong elementCount = new AtomicLong();
	private FileHeader header;

	private Queue<Long> freeOffsets = new ArrayDeque<>();

	public NodeRelationshipStorageImpl(Path path) throws IOException {
		super(path);
		this.header = new FileHeader(path, HEADER_FILE_TYPE);
		this.elementCount.set(header.loadCount());
	}

	@Override
	public List<RelationshipReferenceInternal> load(long startOffset) throws IOException {
		List<RelationshipReferenceInternal> list = new ArrayList<>();
		try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.READ)) {
			long offset = startOffset;
			while (true) {
				long segmentOffset = offset;
				MemorySegment segment = fc.map(MapMode.READ_ONLY, segmentOffset, LAYOUT.byteSize(), Arena.ofAuto());
				offset = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).get(segment, 0);
				long rel = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(REL_OFFSET_KEY)).get(segment, 0);
				boolean free = (boolean) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(FREE_KEY)).get(segment, 0);
				long node = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NODE_OFFSET_KEY)).get(segment, 0);
				System.out.println(
					"rel data at " + segmentOffset + " points to relationship " + rel + " and node " + node + " with next pointing to " + offset
						+ " [" + free + "]");
				if (!free) {
					list.add(new RelationshipReferenceInternal(offset, rel, node));
				}
				if (offset == NO_NEXT_SEGMENT) {
					System.out.println("No further segments. Aborting");
					break;
				}
			}
			return list;
		}

	}

	@Override
	public void deleteByRelOffset(long startOffset, long relOffset) throws IOException {
		try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			ChainInfo chainInfo = followChain(fc, startOffset, segment -> {
				long rel = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(REL_OFFSET_KEY)).get(segment, 0);
				return rel == relOffset;
			});
			MemorySegment prev = chainInfo.prev();
			MemorySegment current = chainInfo.current();
			long currentOffset = chainInfo.currentOffset();
			long nextOffset = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).get(current, 0);

			// There is no next - we just set the next to -1 to indicate the prev is the only item in the chain
			if (prev != null && nextOffset == NO_NEXT_SEGMENT) {
				LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).set(prev, 0, NO_NEXT_SEGMENT);
			}
			// Mark prev -> next and skip current
			if (prev != null && nextOffset != NO_NEXT_SEGMENT) {
				LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).set(prev, 0, nextOffset);
			}
			freeOffsets.add(currentOffset);
			LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(FREE_KEY)).set(current, 0, true);
		}
	}

	@Override
	public long create(long startOffset, long relOffset, long nodeOffset) throws IOException {
		try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			// Initial offset is -1 thus create a new segment for the data and return the start offset
			if (startOffset == NO_NEXT_SEGMENT) {
				long offset = allocateSegment(fc);
				writeSegment(fc, offset, relOffset, nodeOffset);
				return offset;
			}

			// Follow the chain until we find the last element
			ChainInfo chainInfo = followChain(fc, startOffset);
			MemorySegment lastSegment = chainInfo.current();

			// Set the pointer in the last segment to the next offset (next segment)
			long nextOffset = allocateSegment(fc);
			LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).set(lastSegment, 0, (long) nextOffset);

			// Populate the next segment with the information
			writeSegment(fc, nextOffset, relOffset, nodeOffset);

			return nextOffset;
		}
	}

	private ChainInfo followChain(FileChannel fc, long startOffset) throws IOException {
		return followChain(fc, startOffset, null);
	}

	private ChainInfo followChain(FileChannel fc, long startOffset, Predicate<MemorySegment> segmentStopCondition) throws IOException {
		long nextOffset = startOffset;
		MemorySegment prev = null;
		while (true) {
			MemorySegment segment = fc.map(MapMode.READ_WRITE, nextOffset, LAYOUT.byteSize(), Arena.ofAuto());
			long offset = (long) LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).get(segment, 0);
			// Check the early stop condition
			if (segmentStopCondition != null && segmentStopCondition.test(segment)) {
				return new ChainInfo(prev, segment, nextOffset);
			}
			System.out.println(nextOffset + " = " + offset);
			// We found the last segment
			if (offset == NO_NEXT_SEGMENT) {
				return new ChainInfo(prev, segment, nextOffset);
			} else {
				nextOffset = offset;
				prev = segment;
			}
		}
	}

	private void writeSegment(FileChannel fc, long offset, long relOffset, long nodeOffset) throws IOException {
		MemorySegment segment = fc.map(MapMode.READ_WRITE, offset, LAYOUT.byteSize(), Arena.ofAuto());
		LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(FREE_KEY)).set(segment, 0, false);
		LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NODE_OFFSET_KEY)).set(segment, 0, (long) nodeOffset);
		LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(NEXT_OFFSET_KEY)).set(segment, 0, NO_NEXT_SEGMENT);
		LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(REL_OFFSET_KEY)).set(segment, 0, (long) relOffset);
	}

	private long allocateSegment(FileChannel fc) throws IOException {
		long count = elementCount.getAndIncrement();
		header.setCount(fc, count);
		return header.size() + (count * LAYOUT.byteSize());
	}

	@Override
	public FileHeader header() {
		return header;
	}

}

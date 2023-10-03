package ru.vk.itmo.khadyrovalmasgali;

import ru.vk.itmo.BaseEntry;
import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class PersistentDao implements Dao<MemorySegment, Entry<MemorySegment>> {

    private static final String SSTABLE_NAME = "sstable";
    private final Path path;
    private final Arena arena;
    private final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> data;

    private final Comparator<MemorySegment> comparator;
    private long offset;

    public PersistentDao(Config config) {
        path = config.basePath().resolve(Path.of(SSTABLE_NAME));
        offset = 0;
        arena = Arena.ofShared();
        comparator = (o1, o2) -> {
            long mismatch = o1.mismatch(o2);
            if (mismatch == o2.byteSize()) {
                return 1;
            } else if (mismatch == o1.byteSize()) {
                return -1;
            } else if (mismatch == -1) {
                return 0;
            }
            return Byte.compare(
                    o1.get(ValueLayout.JAVA_BYTE, mismatch),
                    o2.get(ValueLayout.JAVA_BYTE, mismatch));
        };
        data = new ConcurrentSkipListMap<>(comparator);
        loadData();
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        if (from == null && to == null) {
            return data.values().iterator();
        } else if (from == null) {
            return data.headMap(to).values().iterator();
        } else if (to == null) {
            return data.tailMap(from).values().iterator();
        } else {
            return data.subMap(from, to).values().iterator();
        }
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        if (data.containsKey(key)) {
            return data.get(key);
        } else {
            return findValueInSSTable(key);
        }
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        data.put(entry.key(), entry);
    }

    @Override
    public void flush() throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ)) {
            long dataSize = 0;
            for (var entry : data.values()) {
                dataSize += 2 * Long.BYTES + entry.key().byteSize() + entry.value().byteSize();
            }
            offset = 0;
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, dataSize, arena);
            for (var entry : data.values()) {
                writeSegment(mappedSegment, entry.key());
                writeSegment(mappedSegment, entry.value());
            }
            mappedSegment.load();
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        arena.close();
    }

    private void loadData() {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ)) {
            offset = 0;
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            while (offset < channel.size()) {
                upsert(new BaseEntry<>(readSegment(mappedSegment), readSegment(mappedSegment)));
            }
        } catch (IOException e) {
            System.out.println("Unable to load data: " + e.getMessage());
        }
    }

    private Entry<MemorySegment> findValueInSSTable(MemorySegment key) {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ)) {
            offset = 0;
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            while (offset < channel.size()) {
                MemorySegment mkey = readSegment(mappedSegment);
                MemorySegment mvalue = readSegment(mappedSegment);
                if (comparator.compare(mkey, key) == 0) {
                    return new BaseEntry<>(mkey, mvalue);
                }
            }
        } catch (IOException e) {
            System.out.println("Unable to load data: " + e.getMessage());
        }
        return null;
    }

    private void writeSegment(MemorySegment mappedSegment, MemorySegment msegment) {
        mappedSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, msegment.byteSize());
        offset += Long.BYTES;
        for (long i = 0; i < msegment.byteSize(); ++i) {
            mappedSegment.set(ValueLayout.JAVA_BYTE, offset, msegment.get(ValueLayout.JAVA_BYTE, i));
            ++offset;
        }
    }

    private MemorySegment readSegment(MemorySegment mappedSegment) {
        long size = mappedSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        offset += Long.BYTES;
        MemorySegment result = mappedSegment.asSlice(offset, size);
        offset += result.byteSize();
        return result;
    }
}

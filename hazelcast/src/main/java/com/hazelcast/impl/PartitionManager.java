/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.impl;

import com.hazelcast.core.HazelcastInstanceAwareObject;
import com.hazelcast.core.Member;
import com.hazelcast.impl.concurrentmap.InitialState;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.partition.MigrationEvent;
import com.hazelcast.partition.MigrationListener;
import com.hazelcast.partition.Partition;
import com.hazelcast.partition.PartitionService;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.hazelcast.impl.ClusterOperation.CONCURRENT_MAP_BLOCKS;
import static com.hazelcast.nio.IOUtil.toData;

public class PartitionManager implements Runnable, PartitionService {

    final Logger logger = Logger.getLogger(PartitionManager.class.getName());

    final ConcurrentMapManager concurrentMapManager;
    final Node node;
    final int BLOCK_COUNT;
    final Block[] blocks;
    final Address thisAddress;
    final List<Block> lsBlocksToMigrate = new ArrayList<Block>(100);

    final long MIGRATION_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10);

    long nextMigrationMillis = System.currentTimeMillis() + MIGRATION_INTERVAL_MILLIS;

    final List<MigrationListener> lsMigrationListeners = new LinkedList<MigrationListener>();

    public PartitionManager(ConcurrentMapManager concurrentMapManager) {
        this.concurrentMapManager = concurrentMapManager;
        this.node = concurrentMapManager.node;
        this.BLOCK_COUNT = concurrentMapManager.BLOCK_COUNT;
        this.blocks = concurrentMapManager.blocks;
        this.thisAddress = concurrentMapManager.thisAddress;
    }

    public void addMigrationListener(final MigrationListener migrationListener) {
        concurrentMapManager.enqueueAndReturn(new Processable() {
            public void process() {
                lsMigrationListeners.add(migrationListener);
            }
        });
    }

    public void removeMigrationListener(final MigrationListener migrationListener) {
        concurrentMapManager.enqueueAndReturn(new Processable() {
            public void process() {
                lsMigrationListeners.remove(migrationListener);
            }
        });
    }

    public Set<Partition> getPartitions() {
        final BlockingQueue<Set<Partition>> responseQ = new ArrayBlockingQueue<Set<Partition>>(1);
        concurrentMapManager.enqueueAndReturn(new Processable() {
            public void process() {
                Set<Partition> partitions = new HashSet<Partition>(BLOCK_COUNT);
                for (int i = 0; i < BLOCK_COUNT; i++) {
                    Block block = blocks[i];
                    if (block == null) {
                        block = concurrentMapManager.getOrCreateBlock(i);
                    }
                    MemberImpl memberOwner = null;
                    if (block.getOwner() != null) {
                        if (thisAddress.equals(block.getOwner())) {
                            memberOwner = concurrentMapManager.thisMember;
                        } else {
                            memberOwner = concurrentMapManager.getMember(block.getOwner());
                        }
                    }
                    partitions.add(new PartitionImpl(block.getBlockId(), memberOwner));
                }
                responseQ.offer(partitions);
            }
        });
        try {
            return responseQ.take();
        } catch (InterruptedException ignored) {
        }
        return null;
    }

    public static class PartitionImpl extends HazelcastInstanceAwareObject implements Partition {
        int partitionId;
        MemberImpl owner;

        PartitionImpl(int partitionId, MemberImpl owner) {
            this.partitionId = partitionId;
            this.owner = owner;
        }

        public int getPartitionId() {
            return partitionId;
        }

        public Member getOwner() {
            return owner;
        }

        public void writeData(DataOutput out) throws IOException {
            super.writeData(out);
            out.writeInt(partitionId);
            boolean hasOwner = (owner != null);
            out.writeBoolean(hasOwner);
            if (hasOwner) {
                owner.writeData(out);
            }
        }

        public void readData(DataInput in) throws IOException {
            super.readData(in);
            partitionId = in.readInt();
            boolean hasOwner = in.readBoolean();
            if (hasOwner) {
                owner = new MemberImpl();
                owner.readData(in);
                owner.setHazelcastInstance(hazelcastInstance);
            }
        }

        @Override
        public String toString() {
            return "Partition [" +
                    +partitionId +
                    "], owner=" + owner;
        }
    }

    public Partition getPartition(Object key) {
        final BlockingQueue<Partition> responseQ = new ArrayBlockingQueue<Partition>(1);
        final Data keyData = toData(key);
        concurrentMapManager.enqueueAndReturn(new Processable() {
            public void process() {
                Block block = blocks[concurrentMapManager.getBlockId(keyData)];
                MemberImpl memberOwner = (block.getOwner() == null) ? null : concurrentMapManager.getMember(block.getOwner());
                responseQ.offer(new PartitionImpl(block.getBlockId(), memberOwner));
            }
        });
        try {
            return responseQ.take();
        } catch (InterruptedException ignored) {
        }
        return null;
    }

    void reArrangeBlocks() {
        if (concurrentMapManager.isMaster()) {
            Map<Address, Integer> addressBlocks = getCurrentMemberBlocks();
            if (addressBlocks.size() == 0) {
                return;
            }
            List<Block> lsBlocksToRedistribute = new ArrayList<Block>();
            int aveBlockOwnCount = BLOCK_COUNT / (addressBlocks.size());
            for (Block blockReal : blocks) {
                if (blockReal.getOwner() == null) {
                    logger.log(Level.SEVERE, "Master cannot have null block owner " + blockReal);
                    return;
                }
                if (blockReal.isMigrating()) {
                    logger.log(Level.SEVERE, "Cannot have migrating block " + blockReal);
                    return;
                }
                Integer countInt = addressBlocks.get(blockReal.getOwner());
                int count = (countInt == null) ? 0 : countInt;
                if (count >= aveBlockOwnCount) {
                    lsBlocksToRedistribute.add(new Block(blockReal));
                } else {
                    addressBlocks.put(blockReal.getOwner(), ++count);
                }
            }
            Collection<Address> allAddress = addressBlocks.keySet();
            lsBlocksToMigrate.clear();
            for (Address address : allAddress) {
                Integer countInt = addressBlocks.get(address);
                int count = (countInt == null) ? 0 : countInt;
                while (count < aveBlockOwnCount && lsBlocksToRedistribute.size() > 0) {
                    Block blockToMigrate = lsBlocksToRedistribute.remove(0);
                    if (!blockToMigrate.getOwner().equals(address)) {
                        blockToMigrate.setMigrationAddress(address);
                        lsBlocksToMigrate.add(blockToMigrate);
                    }
                    count++;
                }
            }
            Collections.shuffle(lsBlocksToMigrate);
        }
    }

    boolean isBlockEmpty(int blockId) {
        Collection<CMap> cmaps = concurrentMapManager.maps.values();
        for (final CMap cmap : cmaps) {
            if (cmap.hasOwned(blockId)) {
                return false;
            }
        }
        return true;
    }

    public boolean containsMigratingBlock() {
        for (Block block : blocks) {
            if (block != null && block.isMigrating()) {
                return true;
            }
        }
        return false;
    }

    public boolean isMigrating(Request req) {
        if (req.key == null && req.blockId != -1 && concurrentMapManager.hashBlocks() != req.blockId) {
            logger.log(Level.FINEST, thisAddress + " blockHashes aren't the same:"
                    + concurrentMapManager.hashBlocks() + ", request.blockId:"
                    + req.blockId + " caller: " + req.caller);
            return true;
        }
        if (req.key != null) {
            Block block = concurrentMapManager.getOrCreateBlock(req.key);
            return block.isMigrating();
        } else {
            for (Block block : blocks) {
                if (block != null && block.isMigrating()) return true;
            }
        }
        return false;
    }

    public void run() {
        removeUnknownRecords();
        long now = System.currentTimeMillis();
        if (now > nextMigrationMillis) {
            nextMigrationMillis = now + MIGRATION_INTERVAL_MILLIS;
            if (!concurrentMapManager.isMaster()) return;
            if (concurrentMapManager.getMembers().size() < 2) return;
            initiateMigration();
        }
    }

    void initiateMigration() {
        boolean hasMigrating = false;
        for (int i = 0; i < BLOCK_COUNT; i++) {
            Block block = blocks[i];
            if (block == null) {
                block = concurrentMapManager.getOrCreateBlock(i);
                block.setOwner(thisAddress);
            }
            if (!hasMigrating && block.isMigrating()) {
                hasMigrating = true;
            }
        }
        if (concurrentMapManager.getMembers().size() < 2) {
            return;
        }
        if (lsBlocksToMigrate.size() == 0) {
            reArrangeBlocks();
        }
        if (!hasMigrating && lsBlocksToMigrate.size() > 0) {
            Block block = lsBlocksToMigrate.remove(0);
            sendBlocks(block);
        }
    }

    public void syncForAdd() {
        if (concurrentMapManager.isMaster()) {
            if (concurrentMapManager.isSuperClient()) {
                MemberImpl nonSuperMember = null;
                for (MemberImpl member : concurrentMapManager.getMembers()) {
                    if (!member.isSuperClient()) {
                        nonSuperMember = member;
                    }
                }
                if (nonSuperMember != null) {
                    for (int i = 0; i < BLOCK_COUNT; i++) {
                        Block block = blocks[i];
                        if (block == null) {
                            block = concurrentMapManager.getOrCreateBlock(i);
                        }
                        if (block.getOwner() == null) {
                            block.setOwner(nonSuperMember.getAddress());
                        }
                    }
                }
            }
            // make sue that all blocks are actually created
            for (int i = 0; i < BLOCK_COUNT; i++) {
                Block block = blocks[i];
                if (block == null) {
                    block = concurrentMapManager.getOrCreateBlock(i);
                }
            }
            quickBlockRearrangement();
            sendBlocks(null);
        }
        InitialState initialState = new InitialState();
        Collection<CMap> cmaps = concurrentMapManager.maps.values();
        for (final CMap cmap : cmaps) {
            initialState.createAndAddMapState(cmap);
        }
        concurrentMapManager.sendProcessableToAll(initialState, false);
        onMembershipChange();
    }

    Map<Address, Integer> getCurrentMemberBlocks() {
        List<MemberImpl> lsMembers = concurrentMapManager.lsMembers;
        Map<Address, Integer> addressBlocks = new HashMap<Address, Integer>();
        for (MemberImpl member : lsMembers) {
            if (!member.isSuperClient()) {
                addressBlocks.put(member.getAddress(), 0);
            }
        }
        return addressBlocks;
    }

    void sendBlocks(Block blockInfo) {
        for (int i = 0; i < BLOCK_COUNT; i++) {
            Block block = blocks[i];
            if (block == null) {
                block = concurrentMapManager.getOrCreateBlock(i);
            }
            if (block.getOwner() == null) {
                block.setOwner(thisAddress);
            }
        }
        Blocks allBlockOwners = new Blocks();
        for (Block block : blocks) {
            if (blockInfo != null && block.getBlockId() == blockInfo.getBlockId()) {
                allBlockOwners.addBlock(blockInfo);
            } else {
                allBlockOwners.addBlock(block);
            }
        }
        allBlockOwners.setNode(node);
        Data dataAllBlocks = ThreadContext.get().toData(allBlockOwners);
        for (MemberImpl member : concurrentMapManager.getMembers()) {
            if (!member.localMember()) {
                concurrentMapManager.send("blocks", CONCURRENT_MAP_BLOCKS, dataAllBlocks, member.getAddress());
            } else {
                allBlockOwners.process();
            }
        }
    }

    private void quickBlockRearrangement() {
        //create all blocks
        for (int i = 0; i < BLOCK_COUNT; i++) {
            Block block = blocks[i];
            if (block == null) {
                block = concurrentMapManager.getOrCreateBlock(i);
            }
            if (block.getOwner() == null) {
                block.setOwner(thisAddress);
            }
        }
        // find storage enabled members
        Map<Address, Integer> addressBlocks = getCurrentMemberBlocks();
        if (addressBlocks.size() == 0) {
            return;
        }
        // find unowned blocks
        // find owned by me but empty blocks
        // distribute them first
        // equally redistribute again
        List<Block> ownableBlocks = new ArrayList<Block>();
        for (Block blockReal : blocks) {
            if (blockReal.getOwner() == null) {
                logger.log(Level.SEVERE, "QBR cannot have block with null owner: " + blockReal);
            } else if (!blockReal.isMigrating() && thisAddress.equals(blockReal.getOwner())) {
                // i am the owner
                // is it empty? can I give that block
                // without migrating
                if (isBlockEmpty(blockReal.getBlockId())) {
                    ownableBlocks.add(blockReal);
                }
            }
            Integer countInt = addressBlocks.get(blockReal.getOwner());
            int count = (countInt == null) ? 0 : countInt;
            addressBlocks.put(blockReal.getOwner(), ++count);
        }
        int aveBlockOwnCount = BLOCK_COUNT / (addressBlocks.size());
        Set<Address> allAddress = addressBlocks.keySet();
        for (Address address : allAddress) {
            Integer countInt = addressBlocks.get(address);
            int count = (countInt == null) ? 0 : countInt;
            while (count < aveBlockOwnCount && ownableBlocks.size() > 0) {
                Block ownableBlock = ownableBlocks.remove(0);
                ownableBlock.setOwner(address);
                count++;
            }
        }
    }

    public void onMembershipChange() {
        lsBlocksToMigrate.clear();
        backupIfNextOrPreviousChanged();
        removeUnknownRecords();
        if (concurrentMapManager.isMaster()) {
            sendBlocks(null);
        }
        nextMigrationMillis = System.currentTimeMillis() + MIGRATION_INTERVAL_MILLIS;
    }

    private void removeUnknownRecords() {
        Collection<CMap> cmaps = concurrentMapManager.maps.values();
        for (CMap cmap : cmaps) {
            Collection<Record> records = cmap.mapRecords.values();
            for (Record record : records) {
                if (record != null) {
                    if (record.isActive()) {
                        Block block = blocks[record.getBlockId()];
                        Address owner = (block.isMigrating()) ? block.getMigrationAddress() : block.getOwner();
                        if (!thisAddress.equals(owner)) {
                            int distance = concurrentMapManager.getDistance(owner, thisAddress);
                            if (distance > cmap.getBackupCount()) {
                                cmap.markAsRemoved(record);
                            }
                        }
                    }
                }
            }
        }
    }

    public void syncForDead(Address deadAddress) {
        if (deadAddress == null || deadAddress.equals(thisAddress)) {
            return;
        }
        Set<Integer> blocksOwnedAfterDead = new HashSet<Integer>();
        for (Block block : blocks) {
            if (block != null) {
                syncForDead(deadAddress, block);
                if (thisAddress.equals(block.getOwner())) {
                    blocksOwnedAfterDead.add(block.getBlockId());
                }
            }
        }
        Collection<CMap> cmaps = concurrentMapManager.maps.values();
        for (CMap cmap : cmaps) {
            Collection<Record> records = cmap.mapRecords.values();
            for (Record record : records) {
                if (record != null) {
                    cmap.onDisconnect(record, deadAddress);
                    if (record.isActive() && blocksOwnedAfterDead.contains(record.getBlockId())) {
                        cmap.markAsOwned(record);
                        // you have to update the indexes
                        // as if this record is new so extract the values first
                        int valueHash = record.getValueHash();
                        long[] indexes = record.getIndexes();
                        byte[] indexTypes = record.getIndexTypes();
                        // set the indexes to null, (new record)
                        record.setValueHash(Integer.MIN_VALUE);
                        record.setIndexes(null, null);
                        // now update the index
                        node.queryService.updateIndex(cmap.getName(), indexes, indexTypes, record, valueHash);
                    }
                }
            }
        }
        onMembershipChange();
    }

    void syncForDead(Address deadAddress, Block block) {
        if (deadAddress.equals(block.getOwner())) {
            MemberImpl member = concurrentMapManager.getNextMemberBeforeSync(deadAddress, true, 1);
            if (member == null) {
                if (!concurrentMapManager.isSuperClient()) {
                    block.setOwner(thisAddress);
                } else {
                    block.setOwner(null);
                }
            } else {
                if (!deadAddress.equals(member.getAddress())) {
                    block.setOwner(member.getAddress());
                } else {
                    block.setOwner(null);
                }
            }
        }
        if (block.isMigrating()) {
            if (deadAddress.equals(block.getMigrationAddress())) {
                MemberImpl member = concurrentMapManager.getNextMemberBeforeSync(deadAddress, true, 1);
                if (member == null) {
                    if (!concurrentMapManager.isSuperClient()) {
                        block.setMigrationAddress(thisAddress);
                    } else {
                        block.setMigrationAddress(null);
                    }
                } else {
                    if (!deadAddress.equals(member.getAddress())) {
                        block.setMigrationAddress(member.getAddress());
                    } else {
                        block.setMigrationAddress(null);
                    }
                }
            }
        }
        for (Block b : blocks) {
            if (b != null && b.isMigrating() && b.getOwner().equals(b.getMigrationAddress())) {
                b.setMigrationAddress(null);
            }
        }
    }

    void backupIfNextOrPreviousChanged() {
        if (node.clusterManager.isNextOrPreviousChanged()) {
            List<Record> lsOwnedRecords = new ArrayList<Record>(1000);
            Collection<CMap> cmaps = concurrentMapManager.maps.values();
            for (final CMap cmap : cmaps) {
                final Object[] records = cmap.ownedRecords.toArray();
                for (Object recObj : records) {
                    final Record rec = (Record) recObj;
                    if (rec.isActive()) {
                        if (rec.getKey() == null || rec.getKey().size() == 0) {
                            throw new RuntimeException("Record.key is null or empty " + rec.getKey());
                        }
                        lsOwnedRecords.add(rec);
                    }
                }
            }
            for (final Record rec : lsOwnedRecords) {
                concurrentMapManager.executeLocally(new FallThroughRunnable() {
                    public void doRun() {
                        concurrentMapManager.backupRecord(rec);
                    }
                });
            }
        }
    }

    void migrateBlock(final Block blockInfo) {
        if (!thisAddress.equals(blockInfo.getOwner())) {
            throw new RuntimeException();
        }
        if (!blockInfo.isMigrating()) {
            throw new RuntimeException();
        }
        if (blockInfo.getOwner().equals(blockInfo.getMigrationAddress())) {
            throw new RuntimeException();
        }
        Block blockReal = blocks[blockInfo.getBlockId()];
        if (blockReal.isMigrationStarted()) {
            return;
        }
        blockReal.setMigrationStarted(true);
        blockReal.setOwner(blockInfo.getOwner());
        blockReal.setMigrationAddress(blockInfo.getMigrationAddress());
        logger.log(Level.FINEST, "migrate blockInfo " + blockInfo);
        if (!node.isActive() || node.factory.restarted) {
            return;
        }
        if (concurrentMapManager.isSuperClient()) {
            return;
        }
        List<Record> lsRecordsToMigrate = new ArrayList<Record>(1000);
        Collection<CMap> cmaps = concurrentMapManager.maps.values();
        for (final CMap cmap : cmaps) {
            if (cmap.locallyOwnedMap != null) {
                cmap.locallyOwnedMap.reset();
            }
            final Object[] records = cmap.ownedRecords.toArray();
            for (Object recObj : records) {
                final Record rec = (Record) recObj;
                if (rec.isActive()) {
                    if (rec.getKey() == null || rec.getKey().size() == 0) {
                        throw new RuntimeException("Record.key is null or empty " + rec.getKey());
                    }
                    if (rec.getBlockId() == blockInfo.getBlockId()) {
                        lsRecordsToMigrate.add(rec);
                        cmap.markAsRemoved(rec);
                    }
                }
            }
        }
        final CountDownLatch latch = new CountDownLatch(lsRecordsToMigrate.size());
        for (final Record rec : lsRecordsToMigrate) {
            final CMap cmap = concurrentMapManager.getMap(rec.getName());
            node.executorManager.executeMigrationTask(new FallThroughRunnable() {
                public void doRun() {
                    try {
                        concurrentMapManager.migrateRecord(cmap, rec);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        node.executorManager.executeMigrationTask(new FallThroughRunnable() {
            public void doRun() {
                try {
                    logger.log(Level.FINEST, "migrate blockInfo " + blockInfo + " await ");
                    latch.await(10, TimeUnit.SECONDS);
                    concurrentMapManager.enqueueAndReturn(new Processable() {
                        public void process() {
                            blockInfo.setOwner(blockInfo.getMigrationAddress());
                            blockInfo.setMigrationAddress(null);
                            completeMigration(blockInfo);
                            sendCompletionInfo(blockInfo);
                        }
                    });
                } catch (InterruptedException ignored) {
                }
            }
        });
    }

    void sendCompletionInfo(Block blockInfo) {
        for (MemberImpl member : concurrentMapManager.lsMembers) {
            if (!member.localMember()) {
                concurrentMapManager.sendBlockInfo(new Block(blockInfo), member.getAddress());
            }
        }
    }

    void completeMigration(Block blockInfo) {
        if (blockInfo.isMigrating()) {
            logger.log(Level.WARNING, "block info cannot be migrating " + blockInfo);
        }
        Block blockReal = blocks[blockInfo.getBlockId()];
        if (blockReal.isMigrating()) {
            fireMigrationEvent(false, new Block(blockReal));
            blockReal.setOwner(blockInfo.getOwner());
            blockReal.setMigrationAddress(null);
            logger.log(Level.FINEST, "Migration complete info : " + blockInfo);
        }
    }

    void handleBlocks(Blocks blockOwners) {
        List<Block> lsBlocks = blockOwners.lsBlocks;
        for (Block block : lsBlocks) {
            Block blockReal = concurrentMapManager.getOrCreateBlock(block.getBlockId());
            if (!sameBlocks(block, blockReal)) {
                if (blockReal.getOwner() == null) {
                    blockReal.setOwner(block.getOwner());
                }
                if (block.getOwner().equals(thisAddress)) {
                    if (!blockReal.getOwner().equals(thisAddress)) {
                        if (blockReal.getOwner().equals(block.getMigrationAddress()) && !blockReal.isMigrating()) {
                            // I already migrated to the new owner but
                            // somehow master doesn't know. maybe master did change in the meantime.
                            sendCompletionInfo(new Block(blockReal));
                        } else {
                            // I think I am not the owner of this block
                            // but block info says i am !!
                            logger.log(Level.SEVERE, blockReal + " block info is inconsistent! " + block);
                        }
                    } else {
                        if (block.isMigrating()) {
                            if (!blockReal.isMigrating()) {
                                // i should start migrating.
                                logger.log(Level.FINEST, "Migration Started " + block);
                                fireMigrationEvent(true, new Block(block));
                                migrateBlock(block);
                            } else {
                                if (!block.getMigrationAddress().equals(blockReal.getMigrationAddress())) {
                                    // i am already migrating to somewhere else!
                                    logger.log(Level.SEVERE, blockReal + " invalid migration address " + block);
                                } else {
                                    // yes. I am in fact migrating to the right address
                                    // check if I am really migrating. Is migration somehow hanging?
                                    if (!blockReal.isMigrationStarted()) {
                                        logger.log(Level.SEVERE, blockReal + " must be migrating but migration didn't even start " + block);
                                    }
                                }
                            }
                        }
                    }
                } else if (blockReal.getOwner().equals(thisAddress)) {
                    // i think i own the block but
                    // block info says i am not!
                    logger.log(Level.WARNING, blockReal + " owner. wrong block info " + block);
                } else {
                    // block is not mine at all, just got the info
                    blockReal.setOwner(block.getOwner());
                    if (blockReal.isMigrating() && !block.isMigrating()) {
                        logger.log(Level.FINEST, "Migration Complete " + block);
                    } else if (!blockReal.isMigrating() && block.isMigrating()) {
                        logger.log(Level.FINEST, "Migration Started " + block);
                        fireMigrationEvent(true, new Block(block));
                    }
                    blockReal.setMigrationAddress(block.getMigrationAddress());
                }
            } else if (blockReal.isMigrating() && thisAddress.equals(blockReal.getOwner())) {
                if (!blockReal.isMigrationStarted()) {
                    fireMigrationEvent(true, new Block(block));
                    migrateBlock(block);
                }
            }
        }
    }

    boolean sameBlocks(Block b1, Block b2) {
        if (b1.getBlockId() != b2.getBlockId()) {
            throw new IllegalArgumentException("Not the same blocks!");
        }
        if (!b1.getOwner().equals(b2.getOwner())) {
            return false;
        }
        if (b1.isMigrating() && !b1.getMigrationAddress().equals(b2.getMigrationAddress())) {
            return false;
        }
        return true;
    }

    void fireMigrationEvent(final boolean started, Block block) {
        if (lsMigrationListeners.size() > 0) {
            final MemberImpl memberOwner = concurrentMapManager.getMember(block.getOwner());
            final MemberImpl memberMigration = concurrentMapManager.getMember(block.getMigrationAddress());
            final MigrationEvent migrationEvent = new MigrationEvent(concurrentMapManager.node, block.getBlockId(), memberOwner, memberMigration);
            for (final MigrationListener migrationListener : lsMigrationListeners) {
                concurrentMapManager.executeLocally(new Runnable() {
                    public void run() {
                        if (started) {
                            migrationListener.migrationStarted(migrationEvent);
                        } else {
                            migrationListener.migrationCompleted(migrationEvent);
                        }
                    }
                });
            }
        }
    }
}

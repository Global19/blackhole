package com.dp.blackhole.supervisor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import com.dp.blackhole.common.PBwrap;
import com.dp.blackhole.common.ParamsKey;
import com.dp.blackhole.common.Util;
import com.dp.blackhole.common.gen.AppRegPB.AppReg;
import com.dp.blackhole.common.gen.AppRollPB.AppRoll;
import com.dp.blackhole.common.gen.AssignConsumerPB.AssignConsumer;
import com.dp.blackhole.common.gen.ColNodeRegPB.ColNodeReg;
import com.dp.blackhole.common.gen.ConfResPB.ConfRes.AppConfRes;
import com.dp.blackhole.common.gen.ConsumerRegPB.ConsumerReg;
import com.dp.blackhole.common.gen.DumpAppPB.DumpApp;
import com.dp.blackhole.common.gen.FailurePB.Failure;
import com.dp.blackhole.common.gen.FailurePB.Failure.NodeType;
import com.dp.blackhole.common.gen.MessagePB.Message;
import com.dp.blackhole.common.gen.MessagePB.Message.MessageType;
import com.dp.blackhole.common.gen.OffsetCommitPB.OffsetCommit;
import com.dp.blackhole.common.gen.ReadyCollectorPB.ReadyCollector;
import com.dp.blackhole.common.gen.RemoveConfPB.RemoveConf;
import com.dp.blackhole.common.gen.RollIDPB.RollID;
import com.dp.blackhole.common.gen.StreamIDPB.StreamID;
import com.dp.blackhole.common.gen.TopicReportPB.TopicReport;
import com.dp.blackhole.common.gen.TopicReportPB.TopicReport.TopicEntry;
import com.dp.blackhole.conf.ConfigKeeper;
import com.dp.blackhole.conf.Context;
import com.dp.blackhole.network.ConnectionFactory;
import com.dp.blackhole.network.EntityProcessor;
import com.dp.blackhole.network.GenServer;
import com.dp.blackhole.network.SimpleConnection;
import com.google.protobuf.InvalidProtocolBufferException;

public class Supervisor {

    public static final Log LOG = LogFactory.getLog(Supervisor.class);
    
    private GenServer<ByteBuffer, SimpleConnection, EntityProcessor<ByteBuffer, SimpleConnection>> server;  
    private ConcurrentHashMap<SimpleConnection, ArrayList<Stream>> connectionStreamMap;
    private ConcurrentHashMap<Stage, SimpleConnection> stageConnectionMap;

    private ConcurrentHashMap<Stream, ArrayList<Stage>> Streams;
    private ConcurrentHashMap<StreamId, Stream> streamIdMap;
    private LionConfChange lionConfChange;
    
    private ConcurrentHashMap<String, ConcurrentHashMap<String ,PartitionInfo>> topics;
    private ConcurrentHashMap<ConsumerGroup, ConsumerGroupDesc> consumerGroups;
  
    private ConcurrentHashMap<SimpleConnection, ConnectionDescription> connections;
    private ConcurrentHashMap<String, SimpleConnection> agentsMapping;
    private ConcurrentHashMap<String, SimpleConnection> brokersMapping;
    
    private void send(SimpleConnection connection, Message msg) {
        if (connection == null || !connection.isActive()) {
            LOG.error("connection is null or closed, message sending abort: " + msg);
            return;
        }
        LOG.debug("send message to " + connection + " :" +msg);
        
        connection.send(PBwrap.PB2Buf(msg));
    }
    
    private void handleHeartBeat(SimpleConnection from) {
        ConnectionDescription desc = connections.get(from);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + from);
            return;
        }
        desc.updateHeartBeat();
    }
    
    private void handleTopicReport(TopicReport report, SimpleConnection from) {
        HashSet<String> TopicsNeedtoReassign = new HashSet<String>();
        
        ConnectionDescription desc = connections.get(from);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + from);
            return;
        }
        BrokerDesc brokerDesc = (BrokerDesc) desc.getAttachment();
        for (TopicEntry entry : report.getEntriesList()) {
            String topic = entry.getTopic();
            String partitionId = entry.getPartitionId();
            
            // record topic and partitions on the broker
            brokerDesc.update(topic, partitionId);
            
            // update partitionInfo, if the partition was offline or new created,
            // consumers may need to reassigned
            ConcurrentHashMap<String, PartitionInfo> partitionInfos = topics.get(topic);
            if (partitionInfos == null) {
                partitionInfos = new ConcurrentHashMap<String, PartitionInfo>();
                topics.put(topic, partitionInfos);
            }
            PartitionInfo partitionInfo = partitionInfos.get(partitionId);
            if (partitionInfo == null) {
                partitionInfo = new PartitionInfo(partitionId, from.getHost(), entry.getOffset());
                partitionInfos.put(partitionId, partitionInfo);
                TopicsNeedtoReassign.add(topic);
            } else {
                if (partitionInfo.isOffline()) {
                    partitionInfo.markOffline(false);
                    TopicsNeedtoReassign.add(topic);
                }
                partitionInfo.setEndOffset(entry.getOffset());
            } 
        }
        
        if (!TopicsNeedtoReassign.isEmpty()) {
            for (Entry<ConsumerGroup, ConsumerGroupDesc> entry : consumerGroups.entrySet()) {
                ConsumerGroup group = entry.getKey();
                ConsumerGroupDesc groupDesc = entry.getValue();
                String topic = group.getTopic();
                // TODO disable at present, first reassign with online partitions in topics
//                Map<String, PartitionInfo> groupPartitions = groupDesc.getPartitions();
                if (TopicsNeedtoReassign.contains(topic)) {
//                    ConcurrentHashMap<String, PartitionInfo> partitionInfos = topics.get(topic);
//                    if (partitionInfos == null) {
//                        LOG.error("can not get partitionInfos from topic: " + topic);
//                        continue;
//                    }
                    // update available partitions
//                    for (PartitionInfo p : partitionInfos.values()) {
//                        String id = p.getId();
//                        if (p.isOffline()) {
//                            if (groupPartitions.containsKey(id)) {
//                                groupPartitions.remove(id);
//                            }
//                        } else {
//                            if (!groupPartitions.containsKey(id)) {
//                                groupPartitions.put(id, new PartitionInfo(p));
//                            }
//                        }
//                    }
                    tryAssignConsumer(null, group, groupDesc);
                }
            }
        }
    }
    
    private void handleConsumerReg(ConsumerReg consumerReg, SimpleConnection from) {
        ConnectionDescription desc = connections.get(from);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + from);
            return;
        }
        desc.setType(ConnectionDescription.CONSUMER);
        
        String groupId = consumerReg.getGroupId();
        String id = consumerReg.getConsumerId();
        String topic = consumerReg.getTopic();

        // get available Partitions
        ConcurrentHashMap<String, PartitionInfo> partitionMap = topics.get(topic);
        if (partitionMap == null) {
            LOG.error("unknown topic: " + topic);
            return;
        }      
        Collection<PartitionInfo> partitions = partitionMap.values();
        ArrayList<PartitionInfo> availPartitions = new ArrayList<PartitionInfo>();
        for (PartitionInfo pinfo : partitions) {
            if (pinfo.isOffline()) {
                continue;
            }
            availPartitions.add(pinfo);
        }
        
        if (availPartitions.size() == 0) {
            LOG.error("no partition available , topic: " + topic);
            return;
        }
        
        ConsumerGroup group = new ConsumerGroup(groupId, topic);
        ConsumerGroupDesc groupDesc = consumerGroups.get(group);
        if (groupDesc == null) {
            groupDesc = new ConsumerGroupDesc(group, availPartitions);
            consumerGroups.put(group, groupDesc);
        }
        
        ConsumerDesc consumerDesc = new ConsumerDesc(id, group, topic, from);
        desc.attach(consumerDesc);
        
        tryAssignConsumer(consumerDesc, group, groupDesc);
    }
    
    public void tryAssignConsumer(ConsumerDesc consumer, ConsumerGroup group, ConsumerGroupDesc groupDesc) {
        Map<ConsumerDesc, ArrayList<String>> consumeMap = groupDesc.getConsumeMap();
//        Map<String, PartitionInfo> partitions = groupDesc.getPartitions();
        
        // new consumer arrived?
        if (consumer != null) {
            if (groupDesc.exists(consumer)) {
                LOG.error("consumer already exists: " + consumer);
                return;
            }
            consumeMap.put(consumer, null);
        }
        
        // calc online partitions
        Map<String, PartitionInfo> allpartitions = topics.get(group.getTopic());
        Map<String, PartitionInfo> partitions = new HashMap<String, PartitionInfo>();
        for (Entry<String, PartitionInfo> entry : allpartitions.entrySet()) {
            if (entry.getValue().isOffline()) {
                continue;
            }
            partitions.put(entry.getKey(), entry.getValue());
        }
        
        // prepare for split
        int consumerNum = consumeMap.keySet().size();
        ArrayList<ArrayList<String>> assignPartitions = new ArrayList<ArrayList<String>>(consumerNum);
        for (int i =0 ; i < consumerNum; i++) {
            assignPartitions.add(new ArrayList<String>());
        }
        
        // split partition into consumerNum groups, and skip offline partitions
        int i =0;
        for (String id : partitions.keySet()) {
            assignPartitions.get(i % consumerNum).add(id);
            i++;
        }
        
        // assign each group to a consumer
        i = 0;
        for (ConsumerDesc c : consumeMap.keySet()) {
            consumeMap.put(c, assignPartitions.get(i));
            i++;
        }
        
        i = 0;
        for (Entry<ConsumerDesc, ArrayList<String>> entry : consumeMap.entrySet()) {
            ConsumerDesc c = entry.getKey();
            ArrayList<String> pinfoList = entry.getValue();
            List<AssignConsumer.PartitionOffset> offsets = new ArrayList<AssignConsumer.PartitionOffset>(pinfoList.size());
            for (String partitionId : pinfoList) {
                PartitionInfo info = partitions.get(partitionId);
                if (info == null) {
                    LOG.error("can not find PartitionInfo by partitionId " + partitionId + ", it shouldn't happen");
                    continue;
                }

                String broker = info.getHost()+ ":" + getBrokerPort(info.getHost());
                AssignConsumer.PartitionOffset offset = PBwrap.getPartitionOffset(broker, info.getId(), info.getEndOffset());
                offsets.add(offset);
            }
            Message assign = PBwrap.wrapAssignConsumer(group.getId(), c.getId(), group.getTopic(), offsets);
            send(c.getConnection(), assign);
            i++;
        }
    }

    private void handleOffsetCommit(OffsetCommit offsetCommit) {
        String id = offsetCommit.getConsumerIdString();
        String groupId = id.split("-")[0];
        String topic = offsetCommit.getTopic();
        String partition = offsetCommit.getPartition();
        long offset = offsetCommit.getOffset();
        
        ConsumerGroup group = new ConsumerGroup(groupId, topic);
        ConsumerGroupDesc groupDesc = consumerGroups.get(group);
        if (groupDesc == null) {
            LOG.error("can not find consumer group " + group);
            return;
        }
        
        groupDesc.updateOffset(id, topic, partition, offset);
    }
    
    /*
     * mark the stream as inactive, mark all the stages as pending unless the uploading stage
     * remove the relationship of the corresponding collectorNode and streams
     */
    private void handleAppNodeFail(ConnectionDescription desc, long now) {
        SimpleConnection connection = desc.getConnection();
        ArrayList<Stream> streams = connectionStreamMap.get(connection);
        if (streams != null) {
            for (Stream stream : streams) {
                LOG.info("mark stream as inactive: " + stream);
                stream.updateActive(false);
                ArrayList<Stage> stages = Streams.get(stream);
                if (stages != null) {
                    synchronized (stages) {
                        for (Stage stage : stages) {
                            LOG.info("checking stage: " + stage);
                            if (stage.status != Stage.UPLOADING) {
                                Issue e = new Issue();
                                e.desc = "logreader failed";
                                e.ts = now;
                                stage.issuelist.add(e);
                                stage.status = Stage.PENDING;
                            }
                        }
                    }
                }
                
                // remove corresponding collectorNodes's relationship with the stream
                String collectorHost = stream.getCollectorHost();
                SimpleConnection collectorConnection = brokersMapping.get(collectorHost);
                if (collectorConnection != null) {
                    ArrayList<Stream> associatedStreams = connectionStreamMap.get(collectorConnection);
                    if (associatedStreams != null) {
                        synchronized (associatedStreams) {
                            associatedStreams.remove(stream);
                            if (associatedStreams.size() == 0) {
                                connectionStreamMap.remove(collectorConnection);
                            }
                        }
                    }
                }
            }
        } else {
            LOG.warn("can not get associate streams from connectionStreamMap by connection: " + connection);
        }
    }
    
    /*
     * 1. process current stage on associated streams
     * 2. remove the relationship of the corresponding appNode and streams
     * 3. processing uploading and recovery stages
     */
    private void handleCollectorNodeFail(ConnectionDescription desc, long now) {
        SimpleConnection connection = desc.getConnection();
        ArrayList<Stream> streams = connectionStreamMap.get(connection);
        // processing current stage on streams
        if (streams != null) {
            for (Stream stream : streams) {
                ArrayList<Stage> stages = Streams.get(stream);
                synchronized (stages) {
                    Stage current = stages.get(stages.size() -1);
                    if (!current.isCurrent()) {
                        LOG.error("stage " + current + "should be current stage");
                        continue;
                    }
                    LOG.info("checking current stage: " + current);
                    
                    Issue e = new Issue();
                    e.desc = "collector failed";
                    e.ts = now;
                    current.issuelist.add(e);

                    // do not reassign collector here, since logreader will find collector fail,
                    // and do appReg again; otherwise two appReg for the same stream will send 
                    if (brokersMapping.size() == 0) {
                        current.status = Stage.PENDING;
                    } else {
                        current.status = Stage.COLLECTORFAIL;
                    }
                    LOG.info("after checking current stage: " + current);
                }
                   
                // remove corresponding appNodes's relationship with the stream
                String appHost = stream.appHost;
                SimpleConnection appConnection = agentsMapping.get(appHost);
                if (appConnection == null) {
                    LOG.error("can not find appConnection by host " + appHost);
                    continue;
                }
                if (appConnection != null) {
                    ArrayList<Stream> associatedStreams = connectionStreamMap.get(appConnection);
                    if (associatedStreams != null) {
                        synchronized (associatedStreams) {
                            associatedStreams.remove(stream);
                            if (associatedStreams.size() == 0) {
                                connectionStreamMap.remove(appConnection);
                            }
                        }
                    }
                }
            }
        } else {
            LOG.warn("can not get associate streams from connectionStreamMap by connection: " + connection);
        }
        
        // processing uploading and recovery stages
        for (Entry<Stage, SimpleConnection> entry : stageConnectionMap.entrySet()) {
            if (connection.equals(entry.getValue())) {
                LOG.info("processing entry: "+ entry);
                Stage stage = entry.getKey();
                if (stage.status == Stage.PENDING) {
                    continue;
                }
                StreamId id = new StreamId(stage.app, stage.apphost);
                Stream stream = streamIdMap.get(id);
                if (stream == null) {
                    LOG.error("can not find stream by streamid: " + id);
                    continue;
                }
                ArrayList<Stage> stages = Streams.get(stream);
                if (stages != null) {
                    synchronized (stages) {
                        if (stream != null) {
                            doRecovery(stream, stage);
                        }
                    }
                }
            }
        }
        
        // mark partitions as offline
        BrokerDesc brokerDesc = (BrokerDesc) desc.getAttachment();
        for (Entry<String, ArrayList<String>> entry : brokerDesc.getPartitions().entrySet()) {
            String topic = entry.getKey();
            ArrayList<String> plist = entry.getValue();
            ConcurrentHashMap<String, PartitionInfo> pinfos = topics.get(topic);
            if (pinfos == null) {
                LOG.error("can not find partitionInfo map by topic " + topic);
                continue;
            }
            synchronized (plist) {
                for (String pid : plist) {
                    PartitionInfo pinfo = pinfos.get(pid);
                    if (pinfo == null) {
                        LOG.error("can not find PartitionInfo by partitionid " + pid);
                        continue;
                    }
                    pinfo.markOffline(true);
                }
            }
        }
    }
    

    private void handleConsumerFail(ConnectionDescription desc, long now) {
        SimpleConnection connection = desc.getConnection();
        LOG.info("consumer " + connection + " disconnectted");
        
        ConsumerDesc consumerDesc = (ConsumerDesc) desc.getAttachment();
        ConsumerGroup group = consumerDesc.getConsumerGroup();
        ConsumerGroupDesc groupDesc = consumerGroups.get(group);
        if (groupDesc == null) {
            LOG.error("can not find groupDesc by ConsumerGroup: " + group);
            return;
        }

        groupDesc.unregisterConsumer(consumerDesc);
        
        if (groupDesc.getConsumers().size() != 0) {
            LOG.info("reassign consumers in group: " + group + ", caused by consumer fail: " + consumerDesc);
            tryAssignConsumer(null, group, groupDesc);
        } else {
            LOG.info("consumerGroup " + group +" has not live consumer, thus be removed");
            consumerGroups.remove(group);
        }
    }
    
    /*
     * cancel the key, remove it from appNodes or collectorNodes, then revisit streams
     * 1. appNode fail, mark the stream as inactive, mark all the stages as pending unless the uploading stage
     * 2. collectorNode fail, reassign collector if it is current stage, mark the stream as pending when no available collector;
     *  do recovery if the stage is not current stage, mark the stage as pending when no available collector
     */
    private void closeConnection(SimpleConnection connection) {
        LOG.info("close connection: " + connection);
        
        long now = Util.getTS();
        ConnectionDescription desc = connections.get(connection);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + connection);
            return;
        }
        String host = connection.getHost();
        if (desc.getType() == ConnectionDescription.AGENT) {
            agentsMapping.remove(host);
            LOG.info("close APPNODE: " + host);
            handleAppNodeFail(desc, now);
        } else if (desc.getType() == ConnectionDescription.BROKER) {
            brokersMapping.remove(host);
            LOG.info("close COLLECTORNODE: " + host);
            handleCollectorNodeFail(desc, now);
        } else if (desc.getType() == ConnectionDescription.CONSUMER) {
            LOG.info("close consumer: " + host);
            handleConsumerFail(desc, now);
        }
        
        connections.remove(connection);
        connectionStreamMap.remove(connection);
    }

    private void dumpstat(SimpleConnection from) {
        StringBuilder sb = new StringBuilder();
        sb.append("dumpstat:\n");
        sb.append("############################## dump ##############################\n");
        
        sb.append("print Streams:\n");
        for (Entry<Stream, ArrayList<Stage>> entry : Streams.entrySet()) {
            Stream stream = entry.getKey();
            sb.append("[stream]\n")
            .append(stream)
            .append("\n")
            .append("[stages]\n");
            ArrayList<Stage> stages = entry.getValue();
            synchronized (stages) {
                for (Stage stage : stages) {
                    sb.append(stage)
                    .append("\n");
                }
            }
        }
        sb.append("\n");
        
        sb.append("print streamIdMap:\n");
        for (Entry<StreamId, Stream> entry : streamIdMap.entrySet()) {
            sb.append("<")
            .append(entry.getValue())
            .append(">")
            .append("\n");
        }
        sb.append("\n");
        
        sb.append("print stageConnectionMap:\n");
        for(Entry<Stage, SimpleConnection> entry : stageConnectionMap.entrySet()) {
            sb.append("<")
            .append(entry.getKey())
            .append(", ")
            .append(entry.getValue())
            .append(">")
            .append("\n");
        }
        sb.append("\n");
        
        sb.append("print appNodes:\n");
        for(SimpleConnection connection: agentsMapping.values()) {
            sb.append("<")
            .append(connection)
            .append(">")
            .append("\n");
        }
        sb.append("\n");
        
        sb.append("print collectorNodes:\n");
        for(SimpleConnection connection: brokersMapping.values()) {
            sb.append("<")
            .append(connection)
            .append(">")
            .append("\n");
        }
        sb.append("\n");
        
        sb.append("print connectionStreamMap:\n");
        for(Entry<SimpleConnection, ArrayList<Stream>> entry : connectionStreamMap.entrySet()) {
            SimpleConnection conn = entry.getKey();
            sb.append(conn)
            .append("\n");
            ArrayList<Stream> streams = entry.getValue();
            synchronized (streams) {
                for (Stream stream : streams) {
                    sb.append(stream)
                    .append("\n");
                }
            }
            sb.append("\n");
        }
        
        sb.append("##################################################################");
        
        String dumpstat = sb.toString();
        LOG.info(dumpstat);
        Message message = PBwrap.wrapDumpReply(dumpstat);
        send(from, message);
    }
    
    private void dumpapp(DumpApp dumpApp, SimpleConnection from) {
        String appName = dumpApp.getAppName();
        StringBuilder sb = new StringBuilder();
        sb.append("dumpapp:\n");
        sb.append("############################## dump ##############################\n");
        sb.append("print streamIdMap:\n");
        Set<Stream> printStreams = new HashSet<Stream>();
        for (StreamId streamId : streamIdMap.keySet()) {
            if (streamId.belongTo(appName)) {
                Stream stream = streamIdMap.get(streamId);
                printStreams.add(stream);
                sb.append("<")
                .append(stream)
                .append(">")
                .append("\n");
            }
        }
        sb.append("\n");
        
        sb.append("print Streams:\n");
        for (Stream stream : printStreams) {
            sb.append("[stream]\n")
            .append(stream)
            .append("\n")
            .append("[stages]\n");
            ArrayList<Stage> stages = Streams.get(stream);
            synchronized (stages) {
                for (Stage stage : stages) {
                    sb.append(stage)
                    .append("\n");
                }
            }
        }
        sb.append("##################################################################");
        
        String dumpstat = sb.toString();
        LOG.info(dumpstat);
        Message message = PBwrap.wrapDumpReply(dumpstat);
        send(from, message);
    }
    
    public void dumpconf(SimpleConnection from) {
        String dumpconf = lionConfChange.dumpconf();
        LOG.info(dumpconf);
        Message message = PBwrap.wrapDumpReply(dumpconf);
        send(from, message);
    }

    public void listApps(SimpleConnection from) {
        StringBuilder sb = new StringBuilder();
        SortedSet<String> appNameSet = new TreeSet<String>();
        sb.append("list apps:\n");
        sb.append("############################## dump ##############################\n");
        
        for(Entry<StreamId, Stream> entry : streamIdMap.entrySet()) {
            String streamIdString = entry.getKey().toString();
            int endIndex = streamIdString.indexOf('@');
            if (endIndex == -1) {
                continue;
            }
            String appName = streamIdString.substring(0, endIndex);
            appNameSet.add(appName);            
        }
        for (String appNameInOrder : appNameSet) {
            sb.append("<")
            .append(appNameInOrder)
            .append(">")
            .append("\n");
        }
        sb.append("##################################################################");
        
        String listApps = sb.toString();
        LOG.info(listApps);
        Message message = PBwrap.wrapDumpReply(listApps);
        send(from, message);
    }

    public void removeConf(RemoveConf removeConf, SimpleConnection from) {
        String appName = removeConf.getAppName();
        List<String> appServers = removeConf.getAppServersList();
        lionConfChange.removeConf(appName, appServers);
    }

    private void handleRetireStream(StreamID streamId) {
        StreamId id = new StreamId(streamId.getAppName(), streamId.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream == null) {
            LOG.error("can't find stream by streamid: " + id);
            return;
        }
        
        if (stream.isActive()) {
            LOG.error("only inactive stream can be retired");
            return;
        } else {
            if (brokersMapping.isEmpty()) {
                LOG.error("only inactive app can be retired");
                return;
            }
            
            LOG.info("retire stream: " + stream);
            
            // remove from streamIdMap
            streamIdMap.remove(id);
            
            // remove the stages from stageConnectionMap
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    for (Stage stage : stages) {
                        stageConnectionMap.remove(stage);
                    }
                }
            } else  {
                LOG.error("can not find stages of stream: " + stream);
            }
            
            // remove stream from connectionStreamMap
            for (Entry<SimpleConnection, ArrayList<Stream>> e : connectionStreamMap.entrySet()) {
                ArrayList<Stream> associatedStreams = e.getValue();
                synchronized (associatedStreams) {
                    associatedStreams.remove(stream);
                    if (associatedStreams.isEmpty()) {
                        connectionStreamMap.remove(e.getKey());
                    }
                }
            }
            
            // remove stream from Streams
            Streams.remove(stream);
        }
    }
    
    private void handleManualRecoveryRoll(RollID rollID) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);

        if (stream != null) {
            // check the stream is active
            if (!stream.isActive()) {
                LOG.error("the manual recovery stage must belong to an active stream");
                return;
            }
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    // process stage missed only
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            if (stage.status != Stage.RECOVERYING && stage.status != Stage.UPLOADING) {
                                doRecovery(stream, stage);
                            } else {
                                LOG.warn("Can't recovery stage manually cause the stage is " + stage.status);
                            }
                            return;
                        }
                    }
                    // create the stage
                    Stage manualRecoveryStage = new Stage();
                    manualRecoveryStage.app = rollID.getAppName();
                    manualRecoveryStage.apphost = rollID.getAppServer();
                    manualRecoveryStage.collectorhost = null;
                    manualRecoveryStage.cleanstart = false;
                    manualRecoveryStage.issuelist = new ArrayList<Issue>();
                    manualRecoveryStage.status = Stage.RECOVERYING;
                    manualRecoveryStage.rollTs = rollID.getRollTs();
                    manualRecoveryStage.isCurrent = false;
                    // put the stage to head of the stages
                    ArrayList<Stage> newStages = new ArrayList<Stage>();
                    newStages.add(manualRecoveryStage);
                    newStages.addAll(stages);
                    Streams.put(stream, newStages);
                    
                    // do recovery
                    doRecovery(stream, manualRecoveryStage);
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }   
    }

    private void handleUnrecoverable(RollID rollID) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);

        if (stream != null) {
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            LOG.error("stage " + stage + " cannot be recovered");
                            stages.remove(stage);
                            stageConnectionMap.remove(stage);
                            break;
                        }
                    }
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }
        
    }
    
    /*
     * failure happened only on stream
     * just log it in a issue, because:
     * if it is a collector fail, the corresponding log reader should find it 
     * and ask for a new collector; if it is a appnode fail, the appNode will
     * register the app again 
     */
    private void handleFailure(Failure failure) {
        StreamId id = new StreamId(failure.getApp(), failure.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream == null) {
            LOG.error("can not find stream by streamid: " + id);
            return;
        }
        
        ArrayList<Stage> stages = Streams.get(stream);
        if (stages == null) {
            LOG.error("can not find stages of stream: " + stream);
            return;
        }
        
        synchronized (stages) {
            long failRollTs = Util.getCurrentRollTs(failure.getFailTs(), stream.period);
            Stage failstage = null;
            for (Stage s : stages) {
                if (s.rollTs == failRollTs) {
                    failstage = s;
                    break;
                }
            }
            // failure message may come before rolling message, and can be ignored here, 
            // since stream reestablish will mark the reconnect issue in current stage
            if (failstage == null) {
                LOG.error("failstage not found: " + failure);
                return;
            }
            if (failure.getType() == NodeType.APP_NODE) {
                Issue i = new Issue();
                i.ts = failure.getFailTs();
                i.desc = "logreader failed";
                failstage.issuelist.add(i);
            } else {
                Issue i = new Issue();
                i.ts = failure.getFailTs();
                i.desc = "collector failed";
                failstage.issuelist.add(i);
            }
        }
    }
    
    /*
     * try to do recovery again when last recovery failed
     */
    private void handleRecoveryFail(RollID rollID) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream != null) {
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            doRecovery(stream, stage);
                            break;
                        }
                    }
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }
    }

    /*
     * mark the stage as uploaded , print summary and remove it from Streams
     */
    private void handleRecoverySuccess(RollID rollID) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);

        if (stream != null) {
            stream.setGreatlastSuccessTs(rollID.getRollTs());
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            stage.status = Stage.UPLOADED;
                            LOG.info(stage);
                            stages.remove(stage);
                            stageConnectionMap.remove(stage);
                            break;
                        }
                    }
                }
            } else {
                LOG.warn("can not find stages of stream: " + stream);
            }
        } else {
            LOG.warn("can't find stream by streamid: " + id);
        }
    }

    /*
     * mark the upload failed stage as recovery
     * add issue to the stage, and do recovery
     */
    private void handleUploadFail(RollID rollID) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);

        if (stream != null) {
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                Issue e = new Issue();
                e.desc = "upload failed";
                e.ts = Util.getTS();
                synchronized (stages) {
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            stage.issuelist.add(e);
                            doRecovery(stream, stage);
                            break;
                        }
                    }
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }
    }

    /*
     * update the stream's lastSuccessTs
     * make the uploaded stage as uploaded and remove it from Streams
     */
    private void handleUploadSuccess(RollID rollID, SimpleConnection from) {
        StreamId id = new StreamId(rollID.getAppName(), rollID.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream != null) {
            stream.setGreatlastSuccessTs(rollID.getRollTs()); 
       
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    for (Stage stage : stages) {
                        if (stage.rollTs == rollID.getRollTs()) {
                            stage.status = Stage.UPLOADED;
                            LOG.info(stage);
                            stages.remove(stage);
                            stageConnectionMap.remove(stage);
                            break;
                        }
                    }
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }
    }
    
    /*
     * current stage rolled
     * do recovery if current stage is not clean start or some issues happpened,
     * or upload the rolled stage;
     * create next stage as new current stage
     */
    private void handleRolling(AppRoll msg, SimpleConnection from) {
        StreamId id = new StreamId(msg.getAppName(), msg.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream != null) {
            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    Stage current = null;
                    for (Stage stage : stages) {
                        if (stage.rollTs == msg.getRollTs()) {
                            current = stage;
                        }
                    }
                    if (current == null) {
                        if (stages.size() > 0) {
                            LOG.error("Stages may missed from stage:" + stages.get(stages.size() - 1)
                                    + " to stage:" + msg.getRollTs());
                        } else {
                            LOG.warn("There are no stages in stream " + stream);
                        }
                        int missedStageCount = getMissedStageCount(stream, msg.getRollTs());
                        LOG.info("need recovery missed stages: " + missedStageCount);
                        for (Stage stage : stages) {
                            stage.isCurrent = false;
                        }
                        Issue issue = new Issue();
                        issue.ts = stream.getlastSuccessTs() + stream.period * 1000;
                        issue.desc = "log discontinuous";
                        ArrayList<Stage> missedStages = getMissedStages(stream, missedStageCount, issue);
                        for (Stage missedStage : missedStages) {
                            LOG.info("processing missed stages: " + missedStage);
                            // check whether it is missed
                            if (missedStage.isCurrent()) {
                                missedStage.isCurrent = false;
                                current = missedStage;
                            }
                            if (!stages.contains(missedStage)) {
                                stages.add(missedStage);
                            }
                            doRecovery(stream, missedStage);
                        }
                    } else {
                        if (current.cleanstart == false || current.issuelist.size() != 0) {
                            current.isCurrent = false;
                            doRecovery(stream, current);
                        } else {
                            current.status = Stage.UPLOADING;
                            current.isCurrent = false;
                            doUpload(stream, current, from);
                        }
                    }
                    // create next stage if stream is connected
                    if (current.status != Stage.PENDING && current.status != Stage.COLLECTORFAIL) {
                        Stage next = new Stage();
                        next.app = stream.app;
                        next.apphost = stream.appHost;
                        next.collectorhost = current.collectorhost;
                        next.cleanstart = true;
                        next.rollTs = current.rollTs + stream.period * 1000;
                        next.status = Stage.APPENDING;
                        next.issuelist = new ArrayList<Issue>();
                        next.isCurrent = true;
                        stages.add(next);
                    }
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        } else {
            LOG.error("can't find stream by streamid: " + id);
        }
    }

    private String doUpload(Stream stream, Stage current, SimpleConnection from) {
        Message message = PBwrap.wrapUploadRoll(current.app, current.apphost, stream.period, current.rollTs);
        send(from, message);
        stageConnectionMap.put(current, from);
        return from.getHost();
    }

    /*
     * do recovery of one stage of a stream
     * if the stream is not active or no collector now,
     * mark the stream as pending
     * else send recovery message
     */
    private String doRecovery(Stream stream, Stage stage) {
        String collector = null;
        collector = getCollector();   

        if (collector == null || !stream.isActive()) {
            stage.status = Stage.PENDING;
            stageConnectionMap.remove(stage);
        } else {
            SimpleConnection c = agentsMapping.get(stream.appHost);
            if (c == null) {
                LOG.error("can not find connection by host: " + stream.appHost);
                return null;
            }
            
            Message message = PBwrap.wrapRecoveryRoll(stream.app, collector, getBrokerPort(collector), stage.rollTs);
            send(c, message);
            
            stage.status = Stage.RECOVERYING;
            stage.collectorhost = collector;
            
            SimpleConnection collectorConnection = brokersMapping.get(collector);
            if (collectorConnection == null) {
                LOG.error("");
            }
            stageConnectionMap.put(stage, collectorConnection);
        }

        return collector;
    }
    
    /*
     * record the stream if it is a new stream;
     * do recovery if it is a old stream
     */
    private void registerStream(ReadyCollector message, SimpleConnection from) {
        long connectedTs = message.getConnectedTs();
        long currentTs = Util.getCurrentRollTs(connectedTs, message.getPeriod());
        
        StreamId id = new StreamId(message.getAppName(), message.getAppServer());
        Stream stream = streamIdMap.get(id);
        
        if (stream == null) {
            // new stream
            stream = new Stream();
            stream.app = message.getAppName();
            stream.appHost = message.getAppServer();
            stream.setCollectorHost(message.getCollectorServer());
            stream.startTs = connectedTs;
            stream.period = message.getPeriod();
            stream.setlastSuccessTs(currentTs - stream.period * 1000);
            
            ArrayList<Stage> stages = new ArrayList<Stage>();
            Stage current = new Stage();
            current.app = stream.app;
            current.apphost = stream.appHost;
            current.collectorhost = message.getCollectorServer();
            current.cleanstart = false;
            current.issuelist = new ArrayList<Issue>();
            current.status = Stage.APPENDING;
            current.rollTs = currentTs;
            current.isCurrent = true;
            stages.add(current);
            Streams.put(stream, stages);
            streamIdMap.put(id, stream);
        } else {
            // old stream reconnected
            LOG.info("stream reconnected: " + stream);
            if (!stream.isActive()) {
                stream.updateActive(true);
            }
            
            // update collectorHost on stream
            stream.setCollectorHost(message.getCollectorServer());

            ArrayList<Stage> stages = Streams.get(stream);
            if (stages != null) {
                synchronized (stages) {
                    recoveryStages(stream, stages, connectedTs, currentTs, message.getCollectorServer());
                }
            } else {
                LOG.error("can not find stages of stream: " + stream);
            }
        }
        
        // register connection with appNode and collectorNode
        recordConnectionStreamMapping(from, stream);
        SimpleConnection appConnection = agentsMapping.get(stream.appHost);
        recordConnectionStreamMapping(appConnection, stream);
    }

    /*
     * 1. recovery appFail or collectFail stages (include current stage, but do no recovery)
     * 2. recovery missed stages (include current stage, but do no recovery)
     * 3. recovery current stage (realtime stream)with collector fail
     */
    private void recoveryStages(Stream stream, ArrayList<Stage> stages, long connectedTs, long currentTs, String newCollectorhost) {
        Issue issue = new Issue();
        issue.ts = connectedTs;
        issue.desc = "stream reconnected";
        
        // recovery Pending and COLLECTORFAIL stages
        for (int i=0 ; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            LOG.info("processing pending stage: " + stage);
            if (stage.status == Stage.PENDING || stage.status == Stage.COLLECTORFAIL) {
                stage.issuelist.add(issue);
                // do not recovery current stage
                if (stage.rollTs != currentTs) {
                    doRecovery(stream, stage);
                } else {
                    // fix current stage status
                    stage.status = Stage.APPENDING;
                    stage.collectorhost = newCollectorhost;
                }
            }
        }

        // recovery missed stages
        int missedStageCount = getMissedStageCount(stream, connectedTs);
        LOG.info("need recovery possible missed stages: " + missedStageCount);

        if (stages.size() != 0) {
            Stage oldcurrent = stages.get(stages.size() - 1);
            if (oldcurrent.rollTs != currentTs) {
                oldcurrent.isCurrent = false;
            }
        } else {
            LOG.error("no stages found on stream: " + stream);
            return;
        }
        ArrayList<Stage> missedStages = getMissedStages(stream, missedStageCount, issue);
        for (Stage stage : missedStages) {
            LOG.info("processing possible missed stages: " + stage);
            // check whether it is missed
            if (!stages.contains(stage)) {
                LOG.info("process really missed stage: " + stage);
                // do not recovery current stage
                if (stage.rollTs != currentTs) {
                    doRecovery(stream, stage);
                } else {
                    stage.status = Stage.APPENDING;
                    stage.collectorhost = newCollectorhost;
                }
                stages.add(stage);
            } else {
                LOG.info("process not really missed stage: " + stage);
                int index = stages.indexOf(stage);
                Stage nmStage = stages.get(index);
                LOG.info("the not missed stage is " + nmStage);
                if (nmStage.rollTs != currentTs) {
                    nmStage.isCurrent = false;
                } else {
                    if (!nmStage.issuelist.contains(issue)) {
                        nmStage.issuelist.add(issue);
                    }
                }
            }
        }
    }
   
    private void recordConnectionStreamMapping(SimpleConnection connection, Stream stream) {
        if (connection == null) {
            LOG.fatal("Connection is null");
            return;
        }
        
        ArrayList<Stream> streams = connectionStreamMap.get(connection);
        if (streams == null) {
            streams = new ArrayList<Stream>();
            connectionStreamMap.put(connection, streams);
        }
        synchronized (streams) {
            if (!streams.contains(stream)) {
                streams.add(stream);
            }
        }
    }
    
    private int getMissedStageCount(Stream stream, long connectedTs) {
        long rollts = Util.getCurrentRollTs(connectedTs, stream.period);
        return (int) ((rollts - stream.getlastSuccessTs()) / stream.period / 1000);
    }
    
    /*
     * caller must hold the monitor of stages
     */
    private ArrayList<Stage> getMissedStages(Stream stream, int missedStageCount, Issue issue) {
        ArrayList<Stage> missedStages = new ArrayList<Stage>();
        for (int i = 0; i< missedStageCount; i++) {
            Stage stage = new Stage();
            stage.app = stream.app;
            stage.apphost = stream.appHost;
            if (i == missedStageCount-1) {
                stage.isCurrent = true;
            } else {
                stage.isCurrent = false;
            }
            stage.issuelist = new ArrayList<Issue>();
            stage.issuelist.add(issue);
            stage.status = Stage.RECOVERYING;
            stage.rollTs = stream.getlastSuccessTs() + stream.period * 1000 * (i+1);
            missedStages.add(stage);
        }
        return missedStages;
    }
    
    /*
     * 1. recored the connection in appNodes
     * 2. assign a collect to the app
     */
    private void registerApp(Message m, SimpleConnection from) {
        ConnectionDescription desc = connections.get(from);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + from);
            return;
        }
        desc.setType(ConnectionDescription.AGENT);
        LOG.info("AppNode " + from.getHost() + " registered");
        agentsMapping.put(from.getHost(), from);
        AppReg message = m.getAppReg();
        assignCollector(message.getAppName(), from);
    }

    private String assignCollector(String app, SimpleConnection from) {
        String broker;
        String previousBroker = null;
        for (Stream stream : Streams.keySet()) {
            if (stream.app.equals(app)) {
                previousBroker = stream.getCollectorHost();
                break;
            }
        }

        Collection<String> brokers = brokersMapping.keySet();
        if (previousBroker!= null && brokers.contains(previousBroker)) {
            broker = previousBroker;
        } else {
            broker = getCollector();
        }
        
        Message message;
        if (broker != null) {
            message = PBwrap.wrapAssignCollector(app, broker, getBrokerPort(broker));
        } else {
            message = PBwrap.wrapNoAvailableNode(app);
        }

        send(from, message);
        return broker;
    }
    
    private void registerCollectorNode(ColNodeReg colNodeReg, SimpleConnection from) {
        ConnectionDescription desc = connections.get(from);
        if (desc == null) {
            LOG.error("can not find ConnectionDesc by connection " + from);
            return;
        }
        desc.setType(ConnectionDescription.BROKER);
        desc.attach(new BrokerDesc(from.toString(), colNodeReg.getPort()));
        brokersMapping.put(from.getHost(), from);
        LOG.info("CollectorNode " + from.getHost() + " registered");
    }

    /* 
     * random return a collector
     * if no collectorNodes now, return null
     */
    private String getCollector() {
        ArrayList<String> array = new ArrayList<String>(brokersMapping.keySet());
        if (array.size() == 0 ) {
            return null;
        }
        Random random = new Random();
        String collector = array.get(random.nextInt(array.size()));
        return collector;
    }
    
    
    public class SupervisorExecutor implements EntityProcessor<ByteBuffer, SimpleConnection> {

        @Override
        public void OnConnected(SimpleConnection connection) {
            ConnectionDescription desc = connections.get(connection);
            if (desc == null) {
                LOG.info("client " + connection + " connected");
                connections.put(connection, new ConnectionDescription(connection));
            } else {
                LOG.error("connection already registered: " + connection);
            }
        }

        @Override
        public void OnDisconnected(SimpleConnection connection) {
            closeConnection(connection);
        }
        
        @Override
        public void process(ByteBuffer request, SimpleConnection from) {
            Message msg = null;
            try {
                msg = PBwrap.Buf2PB(from.getEntity());
            } catch (InvalidProtocolBufferException e) {
                LOG.error("InvalidProtocolBufferException catched: ", e);
                return;
            }
            
            if (msg.getType() != MessageType.HEARTBEART 
                    && msg.getType() != MessageType.TOPICREPORT
                    && msg.getType() != MessageType.OFFSET_COMMIT) {
                LOG.debug("received: " + msg);
            }
            
            switch (msg.getType()) {
            case HEARTBEART:
                handleHeartBeat(from);
                break;
            case COLLECTOR_REG:
                registerCollectorNode(msg.getColNodeReg(), from);
                break;
            case APP_REG:
                registerApp(msg, from);
                break;
            case READY_COLLECTOR:
                registerStream(msg.getReadyCollector(), from);
                break;
            case APP_ROLL:
                handleRolling(msg.getAppRoll(), from);
                break;
            case UPLOAD_SUCCESS:
                handleUploadSuccess(msg.getRollID(), from);
                break;
            case UPLOAD_FAIL:
                handleUploadFail(msg.getRollID());
                break;
            case RECOVERY_SUCCESS:
                handleRecoverySuccess(msg.getRollID());
                break;
            case RECOVERY_FAIL:
                handleRecoveryFail(msg.getRollID());
                break;
            case FAILURE:
                handleFailure(msg.getFailure());
                break;
            case UNRECOVERABLE:
                handleUnrecoverable(msg.getRollID());
                break;
            case MANUAL_RECOVERY_ROLL:
                handleManualRecoveryRoll(msg.getRollID());
                break;
            case DUMPSTAT:
                dumpstat(from);
                break;
            case RETIRESTREAM:
                handleRetireStream(msg.getStreamId());
                break;
            case CONF_REQ:
                findConfs(from);
                break;
            case DUMPCONF:
                dumpconf(from);
                break;
            case LISTAPPS:
                listApps(from);
                break;
            case REMOVE_CONF:
                removeConf(msg.getRemoveConf(), from);
                break;
            case DUMP_APP:
                dumpapp(msg.getDumpApp(), from);
                break;
            case TOPICREPORT:
                handleTopicReport(msg.getTopicReport(), from);
                break;
            case CONSUMER_REG:
                handleConsumerReg(msg.getConsumerReg(), from);
                break;
            case OFFSET_COMMIT:
                handleOffsetCommit(msg.getOffsetCommit());
                break;
            default:
                LOG.warn("unknown message: " + msg.toString());
            }
        }
    }
    
    private class LiveChecker extends Thread {
        boolean running = true;
        
        @Override
        public void run() {
            int THRESHOLD = 15 * 1000;
            while (running) {
                try {
                    Thread.sleep(5000);
                    long now = Util.getTS();
                    for (Entry<SimpleConnection, ConnectionDescription> entry : connections.entrySet()) {
                        SimpleConnection conn = entry.getKey();
                        if (now - entry.getValue().getLastHeartBeat() > THRESHOLD) {
                            LOG.info("failed to get heartbeat for 15 seconds, close connection " + conn);
                            closeConnection(conn);
                        }
                    }
                } catch (InterruptedException e) {
                    LOG.info("LiveChecker thread interrupted");
                    running =false;
                }
            }
        }
    }
       
//    private void init() throws IOException, ClosedChannelException, LionException {

    private void init() throws IOException, LionException {
        Properties prop = new Properties();
        prop.load(ClassLoader.getSystemResourceAsStream("config.properties"));

        connectionStreamMap = new ConcurrentHashMap<SimpleConnection, ArrayList<Stream>>();
        stageConnectionMap = new ConcurrentHashMap<Stage, SimpleConnection>();

        Streams = new ConcurrentHashMap<Stream, ArrayList<Stage>>();
        streamIdMap = new ConcurrentHashMap<StreamId, Stream>();

        //initLion(or initZooKeeper)
        connectAppConfKeeper(prop);
        
        topics = new ConcurrentHashMap<String, ConcurrentHashMap<String,PartitionInfo>>();
        consumerGroups = new ConcurrentHashMap<ConsumerGroup, ConsumerGroupDesc>();
        
        connections = new ConcurrentHashMap<SimpleConnection, ConnectionDescription>();
        agentsMapping = new ConcurrentHashMap<String, SimpleConnection>();
        brokersMapping = new ConcurrentHashMap<String, SimpleConnection>();
        
        // start heart beat checker thread
        LiveChecker checker = new LiveChecker();
        checker.setDaemon(true);
//        checker.start();
        
        SupervisorExecutor executor = new SupervisorExecutor();
        ConnectionFactory<SimpleConnection> factory = new SimpleConnection.SimpleConnectionFactory();
        server = new GenServer<ByteBuffer, SimpleConnection, EntityProcessor<ByteBuffer, SimpleConnection>>
            (executor, factory, null);

        server.init(prop, "supervisor", "supervisor.port");
    }

    private void connectAppConfKeeper(Properties prop) throws IOException, LionException {
        ConfigCache configCache = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress());
        lionConfChange = new LionConfChange(configCache);
        lionConfChange.initLion();
    }
    
    public void findConfs(SimpleConnection from) {
        Message message;
        List<String> appNamesInOneHost;
        if ((appNamesInOneHost = lionConfChange.hostToAppNames.get(from.getHost())) == null) {
            message = PBwrap.wrapNoAvailableConf();
            LOG.info("Hosts for app configurations are not ready, send NoAvailableConf message to " + from.getHost());
            send(from, message);
            return;
        }
        List<AppConfRes> appConfResList = new ArrayList<AppConfRes>();
        for (String appName : appNamesInOneHost) {
            Context context = ConfigKeeper.configMap.get(appName);
            String watchFile = context.getString(ParamsKey.Appconf.WATCH_FILE);
            if (context == null || watchFile == null) {
                message = PBwrap.wrapNoAvailableConf();
                send(from, message);
                return;
            }
            String period = context.getString(ParamsKey.Appconf.ROLL_PERIOD);
            String maxLineSize = context.getString(ParamsKey.Appconf.MAX_LINE_SIZE);
            AppConfRes appConfRes = PBwrap.wrapAppConfRes(appName, watchFile, period, maxLineSize);
            appConfResList.add(appConfRes);
        }
        message = PBwrap.wrapConfRes(appConfResList);
        send(from, message);  
    }
    
    private int getBrokerPort(String host) {
        SimpleConnection connection = brokersMapping.get(host);
        if (connection == null) {
            LOG.error("can not get connection from host: " + host);
            return 0;
        }
        ConnectionDescription desc = connections.get(connection);
        if (desc == null) {
            LOG.error("can not get ConnectionDescription from connection: " + connection);
            return 0;
        }
        BrokerDesc brokerDesc = (BrokerDesc) desc.getAttachment();
        return brokerDesc.getPort();
    }
    
    /**
     * @param args
     * @throws IOException 
     * @throws LionException
     */
    public static void main(String[] args) throws IOException, LionException {
        Supervisor supervisor = new Supervisor();
        supervisor.init();
    }

}
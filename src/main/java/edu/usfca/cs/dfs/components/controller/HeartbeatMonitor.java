package edu.usfca.cs.dfs.components.controller;

import edu.usfca.cs.dfs.DFSProperties;
import edu.usfca.cs.dfs.structures.ComponentAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;
import java.util.Set;

public class HeartbeatMonitor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatMonitor.class);
    private final Set<ComponentAddress> onlineStorageNodes;
    private final Map<ComponentAddress, Date> heartbeats;
    private final FileTable fileTable;

    public HeartbeatMonitor(Set<ComponentAddress> onlineStorageNodes, Map<ComponentAddress, Date> heartbeats, FileTable fileTable) {
        this.onlineStorageNodes = onlineStorageNodes;
        this.heartbeats = heartbeats;
        this.fileTable = fileTable;
    }

    @Override
    public void run() {
        int heartbeatCheckPeriod = DFSProperties.getInstance().getHeartbeatCheckPeriod();
        int maxHeartbeatAge = DFSProperties.getInstance().getMaxHeartbeatAge();

        try {
            while (true) {
                for (Map.Entry<ComponentAddress, Date> entry : heartbeats.entrySet()) {
                    Date lastHeartbeat = entry.getValue();
                    Date now = new Date();
                    long differenceMilliseconds = now.getTime() - lastHeartbeat.getTime();
                    if (differenceMilliseconds > maxHeartbeatAge) {
                        // Uh oh
                        ComponentAddress storageNode = entry.getKey();
                        logger.warn("Haven't received any heartbeat from " + storageNode + " for the past " + differenceMilliseconds + " ms. Marking as unavailable.");
                        if (onlineStorageNodes.contains(storageNode)) {
                            onlineStorageNodes.remove(storageNode);
                        }
                        fileTable.onStorageNodeOffline(storageNode);
                    }
                }

                Thread.sleep(heartbeatCheckPeriod);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
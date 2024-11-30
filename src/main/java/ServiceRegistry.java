import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class ServiceRegistry implements Watcher {
    private static final String SERVICES_ZNODE = "/services";  // Root path for services in ZooKeeper
    private final String zookeeperAddress;
    private final int sessionTimeout;
    private ZooKeeper zooKeeper;
    private final Map<String, String> registry = new ConcurrentHashMap<>();
    private final CountDownLatch connectedSignal = new CountDownLatch(1);

    // Constructor
    public ServiceRegistry(String zookeeperAddress, int sessionTimeout) {
        this.zookeeperAddress = zookeeperAddress;
        this.sessionTimeout = sessionTimeout;
    }

    // Connect to Zookeeper
    public void connectToZookeeper() throws IOException, InterruptedException {
        zooKeeper = new ZooKeeper(zookeeperAddress, sessionTimeout, this);
        connectedSignal.await(); // Wait until connected
    }

    // Process events from Zookeeper
    @Override
    public void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            System.out.println("Successfully connected to Zookeeper");
            connectedSignal.countDown(); // Signal that connection is established
        } else if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            System.out.println("Disconnected from Zookeeper");
        }

        if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged && SERVICES_ZNODE.equals(event.getPath())) {
            updateRegistry();
        }
    }

    // Keep the application running to process Zookeeper events
    public void run() throws InterruptedException {
        synchronized (this) {
            wait();
        }
    }

    // Close the Zookeeper connection
    public void close() throws InterruptedException {
        if (zooKeeper != null) {
            zooKeeper.close();
            System.out.println("Disconnected from Zookeeper");
        }
    }

    // Initialize the service registry
    public void initialise() throws KeeperException, InterruptedException {
        // Ensure /services exists, if not, create it
        if (zooKeeper.exists(SERVICES_ZNODE, false) == null) {
            zooKeeper.create(SERVICES_ZNODE, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Created root services node: " + SERVICES_ZNODE);
        }

        // Watch and get all children (services) under the /services node
        List<String> serviceNames = zooKeeper.getChildren(SERVICES_ZNODE, this);

        // For each service, retrieve its address and store it in the registry
        for (String serviceName : serviceNames) {
            String servicePath = SERVICES_ZNODE + "/" + serviceName;
            byte[] data = zooKeeper.getData(servicePath, event -> {
                if (event.getType() == Event.EventType.NodeDataChanged) {
                    updateRegistry(serviceName);
                }
            }, null);
            String serviceAddress = new String(data);
            registry.put(serviceName, serviceAddress);
        }
    }

    // Update the registry for a specific service when its data changes
    private void updateRegistry(String serviceName) {
        try {
            String servicePath = SERVICES_ZNODE + "/" + serviceName;
            byte[] data = zooKeeper.getData(servicePath, false, null);
            String updatedAddress = new String(data);
            registry.put(serviceName, updatedAddress);  // Update the registry with new address
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Update the registry for all services by fetching the latest data from ZooKeeper
    private void updateRegistry() {
        registry.clear();
        try {
            // Get the list of children under /services
            List<String> children = zooKeeper.getChildren("/services", true);
            for (String child : children) {
                byte[] data = zooKeeper.getData("/services/" + child, false, null);
                registry.put(child, new String(data));
            }
            System.out.println("Current Registry: " + registry);
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Get the current service registry
    public Map<String, String> getServices() {
        return new ConcurrentHashMap<>(registry);
    }

    // Register or update a service in the registry
    public void register(String serviceName, String serviceAddress) throws KeeperException, InterruptedException {
        String path = SERVICES_ZNODE + "/" + serviceName;

        Stat stat = zooKeeper.exists(path, false);
        if (stat == null) {
            // If it doesn't exist, create it as an ephemeral node
            zooKeeper.create(path, serviceAddress.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            System.out.println("Registered service: " + serviceName + " at address: " + serviceAddress);
        } else {
            // If it exists, update the data
            zooKeeper.setData(path, serviceAddress.getBytes(), stat.getVersion());
            System.out.println("Updated service: " + serviceName + " to address: " + serviceAddress);
        }

        // Update the local registry map directly
        registry.put(serviceName, serviceAddress);
        System.out.println("Local registry updated: " + registry);
    }

}

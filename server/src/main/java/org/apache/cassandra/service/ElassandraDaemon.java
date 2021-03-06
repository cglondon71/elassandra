/*
 * Copyright (c) 2017 Strapdata (http://www.strapdata.com)
 * Contains some code from Elasticsearch (http://www.elastic.co)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.service;

import com.google.common.util.concurrent.Uninterruptibles;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.config.SchemaConstants;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.NativeLibrary;
import org.apache.cassandra.utils.WindowsTimer;
import org.apache.logging.log4j.Logger;
import org.elassandra.NoPersistedMetaDataException;
import org.elassandra.discovery.CassandraDiscovery;
import org.elassandra.index.ElasticSecondaryIndex;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.bootstrap.Bootstrap;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.spi.Message;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.plugins.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import static com.google.common.collect.Sets.newHashSet;


/**
 * Bootstrap sequence Cassandra setup() joinRing() beforBoostrap()  wait for
 * local shard = STARTED ElasticSearch activate() GatewayService recover
 * metadata from cassandra schema DiscoveryService discover ring topology and
 * build routing table await that Cassandra start() (Complet cassandra
 * bootstrap) ElasticSearch start() (Open Elastic http service)
 *
 *
 * @author vroyer
 *
 */
public class ElassandraDaemon extends CassandraDaemon {
    private static final Logger logger = Loggers.getLogger(ElassandraDaemon.class);

    private static volatile Thread keepAliveThread;
    private static volatile CountDownLatch keepAliveLatch;

    public static ElassandraDaemon instance;
    public static boolean hasWorkloadColumn = false;
    
    protected volatile Node node = null;
    protected Environment env;
    
    private boolean needsMappingFromSchema = false;
    private boolean activated = false;
    
    private MetaData systemMetadata = null;
    private List<SetupListener> setupListeners = new CopyOnWriteArrayList();
    
    public ElassandraDaemon(Environment env) {
        super(true);
        this.env = env;
    }
    
    public Node node() {
        return this.node;
    }
    
    public Node node(Node node) {
        this.node = node;
        return node;
    }
    
    public void activate(boolean addShutdownHook, boolean createNode, Settings settings, Environment env, Collection<Class<? extends Plugin>> pluginList) {
        try
        {
            DatabaseDescriptor.daemonInitialization();
            DatabaseDescriptor.createAllDirectories();
        }
        catch (ExceptionInInitializerError e)
        {
            System.out.println("Exception (" + e.getClass().getName() + ") encountered during startup: " + e.getMessage());
            String errorMessage = buildErrorMessage("Initialization", e);
            System.err.println(errorMessage);
            System.err.flush();
            System.exit(3);
        }
        
        if (FBUtilities.isWindows)
        {
            // We need to adjust the system timer on windows from the default 15ms down to the minimum of 1ms as this
            // impacts timer intervals, thread scheduling, driver interrupts, etc.
            WindowsTimer.startTimerPeriod(DatabaseDescriptor.getWindowsTimerInterval());
        }
        
        String pidFile = System.getProperty("cassandra-pidfile");
        if (pidFile != null)
        {
            new File(pidFile).deleteOnExit();
        }
        
        // look for jar hell
        /*
        try {
            JarHell.checkJarHell();
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
        }
         */
        
        // #202 Initialize NativeLibrary.jnaLockable for correct Node initialization.
        NativeLibrary.tryMlockall();
        
        //setup(addShutdownHook, settings, env, pluginList);
        org.elasticsearch.bootstrap.Bootstrap.initializeNatives(
                env.tmpFile(),
                settings.getAsBoolean("bootstrap.memory_lock", true),
                settings.getAsBoolean("bootstrap.system_call_filter", false),
                settings.getAsBoolean("bootstrap.ctrlhandler", true));

        // initialize probes before the security manager is installed
        org.elasticsearch.bootstrap.Bootstrap.initializeProbes();
        
        if (addShutdownHook) {
          Runtime.getRuntime().addShutdownHook(new Thread() {
              @Override
              public void run() {
                  if (node != null) {
                      try {
                          node.close();
                      } catch (IOException e) {
                          throw new RuntimeException(e);
                      }
                  }
              }
          });
        }
        
        // install SM after natives, shutdown hooks, etc.
        //org.elasticsearch.bootstrap.Bootstrap.setupSecurity(settings, environment);
    
        if (createNode) {
            this.node = new Node(getSettings(), pluginList);
        }
        
        //enable indexing in cassandra.
        ElasticSecondaryIndex.runsElassandra = true;
        
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(new StandardMBean(new NativeAccess(), NativeAccessMBean.class), new ObjectName(MBEAN_NAME));
        } catch (Exception e) {
            logger.error("error registering MBean {}", MBEAN_NAME, e);
            // Allow the server to start even if the bean can't be registered
        }
        
        if (FBUtilities.isWindows)
        {
            // We need to adjust the system timer on windows from the default 15ms down to the minimum of 1ms as this
            // impacts timer intervals, thread scheduling, driver interrupts, etc.
            WindowsTimer.startTimerPeriod(DatabaseDescriptor.getWindowsTimerInterval());
        }
        
        // Set workload it to "elasticsearch" if column workload exists.
        try {
            ColumnIdentifier workload = new ColumnIdentifier("workload",false);
            CFMetaData local = SystemKeyspace.metadata().getTableOrViewNullable(SystemKeyspace.LOCAL);
            if (local.getColumnDefinition(workload) != null) {
                QueryProcessor.executeOnceInternal("INSERT INTO system.local (key, workload) VALUES (?,?)" , new Object[] { "local","elasticsearch" });
                logger.info("Internal workload set to elasticsearch");
                
                CFMetaData system = SystemKeyspace.metadata().getTableOrViewNullable(SystemKeyspace.LOCAL);
                hasWorkloadColumn = system.getColumnDefinition(workload) != null;
            }
        } catch (Exception e1) {
            logger.error("Failed to set the workload to elasticsearch.",e1);
        }
        
        super.setup(); // start bootstrap CassandraDaemon
        super.start(); // start Thrift+RPC service

        
        if (this.needsMappingFromSchema)
            this.node.clusterService().submitNumberOfShardsAndReplicasUpdate("user-keyspaces-bootstraped");
        
        if (this.node != null) {
            try {
                this.node.start();
            } catch (NodeValidationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Start Node if metadata available.
     */
    @Override
    public void systemKeyspaceInitialized() {
        if (node != null) {
            try {
                systemMetadata = this.node.clusterService().readMetaDataAsComment();
                if (systemMetadata != null) {
                    activateAndWaitShards("before opening user keyspaces");
                }
            } catch(NoPersistedMetaDataException e) {
                this.needsMappingFromSchema = true;
                logger.info("Elasticsearch activation delayed after boostraping, no mapping available");
            } catch(Throwable e) {
                logger.warn("Unexpected error",e);
            }
        }
    }
    
    @Override
    public void userKeyspaceInitialized() {
        if (node != null && !needsMappingFromSchema) {
            // try to read a newer metadata from the local elastic_admin.metadata table.
            MetaData metaData = this.node.clusterService().readInternalMetaDataAsRow();
            if (metaData != null && metaData.version() > systemMetadata.version()) {
                try {
                    logger.warn("Refreshing newer metadata found in {}.{} metaData uuid={} version={}", 
                            ClusterService.ELASTIC_ADMIN_KEYSPACE, ClusterService.ELASTIC_ADMIN_METADATA_TABLE,
                            metaData.clusterUUID(), metaData.version());
                    node.clusterService().submitRefreshMetaData(metaData, "metadatarefresh");
                    
                    if (metaData.clusterUUID() == this.node.clusterService().localNode().getId()) {
                        logger.warn("Saving the new metadata metaData.uuid={} version={} in the CQL schema",
                                metaData.clusterUUID(), metaData.version());
                        node.clusterService().writeMetaDataAsComment(metaData);
                    }
                } catch (Exception e) {
                    logger.error("Unexpected error",e);
                }
            }
            
            node.clusterService().submitNumberOfShardsAndReplicasUpdate("user-keyspaces-initialized");
        }
    }
    
    public interface SetupListener {
        public void onComplete();
    }
    
    public void register(SetupListener listener) {
        setupListeners.add(listener);
    }
    
    public void completeSetup() 
    {
        super.completeSetup();
        /*
        if (this.node != null && this.node.clusterService() != null) 
            this.node.clusterService().publishGossipStates();
        */
        for(SetupListener listener : setupListeners)
            listener.onComplete();
    }
    
    /**
     * Called just before boostraping, and after CQL schema is complete, 
     * so elasticsearch mapping may be available from the CQL schema.
     */
    @Override
    public void beforeBootstrap() {
        if (node != null && needsMappingFromSchema)
        {
            try 
            {
                // load mapping from schema jsut before bootstrapping C*
                systemMetadata = this.node.clusterService().readMetaDataAsComment();
                activateAndWaitShards("before cassandra boostraping");
            } catch(Throwable e) 
            {
                logger.error("Failed to load Elasticsearch mapping from CQL schema before bootstraping:", e);
            }
        }
    }
    
    /**
     * Called when C* does not bootstrap, and before CQL schema is completed.
     * If we don't have yet found the elasticsearch mapping from the CQL schema, wait for the CQL schema to complete before trying to start elasticsearch.
     * Obviously, the first seed node will wait (30s by default) for nothing (or use cassandra.ring_delay_ms=0).
     */
    public void ringReady() 
    {
        if (node != null) 
        {
            // wait for schema before starting elasticsearch node
            if (needsMappingFromSchema && !activated)  
            {
                try 
                {
                    logger.info("waiting "+StorageService.RING_DELAY+"ms for CQL schema to get the elasticsearch mapping");
                    // first sleep the delay to make sure we see all our peers
                    for (int i = 0; i < StorageService.RING_DELAY; i += 1000)
                    {
                        // if we see schema, we can proceed to the next check directly
                        if (!Schema.instance.getVersion().equals(SchemaConstants.emptyVersion))
                        {
                            logger.debug("got schema: {}", Schema.instance.getVersion());
                            break;
                        }
                        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                    }
                    
                    // if our schema hasn't matched yet, wait until it has
                    if (!MigrationManager.isReadyForBootstrap())
                    {
                        logger.info("waiting for schema information to complete");
                        MigrationManager.waitUntilReadyForBootstrap();
                    }
                    systemMetadata = this.node.clusterService().readMetaDataAsComment();
                } catch(Throwable e) 
                {
                    logger.warn("Failed to load elasticsearch mapping from CQL schema after after joining without boostraping:", e);
                }
            }
            activateAndWaitShards((systemMetadata == null) ? "with empty elasticsearch mapping" : "after getting the elasticsearch mapping from CQL schema");
        }
    }
    
    public void activateAndWaitShards(String source) 
    {
        if (!activated) {
            activated = true;
            logger.info("Activating Elasticsearch, shards starting "+source);
            node.activate();
            node.clusterService().addShardStartedBarrier();
            node.clusterService().blockUntilShardsStarted();
            logger.info("Elasticsearch shards started, ready to go on.");
        }
    }

    /**
     * hook for JSVC
     */
    @Override
    public void start() {
        super.start();
    }

    /**
     * hook for JSVC
     */
    @Override
    public void stop() {
        super.stop();
        if (node != null)
            node.stop();
    }

    /**
     * hook for JSVC
     */
    public void init(String[] args) {

    }

    /**
     * hook for JSVC
     */
    public void activate() {
        if (node != null)
            node.activate();
    }
    
    /**
     * hook for JSVC
     */
    public void destroy() {
        super.destroy();
        if (node != null) {
            try {
                node.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (keepAliveLatch != null)
            keepAliveLatch.countDown();
    }

    public Node newNode(Settings settings, Collection<Class<? extends Plugin>> classpathPlugins) {
        Settings nodeSettings = nodeSettings(settings);
        System.out.println("node settings="+nodeSettings.getAsGroups());
        System.out.println("node plugins="+classpathPlugins);
        this.node = new Node(nodeSettings, classpathPlugins);
        return this.node;
    }
   
    public Settings getSettings() {
        return nodeSettings(env.settings());
    }
    
    public Settings nodeSettings(Settings settings) {
        return Settings.builder()
                 // overloadable settings from elasticsearch.yml
                 // by default, HTTP is bound to C* rpc address, Transport is bound to C* internal listen address.
                .put("network.bind_host", DatabaseDescriptor.getRpcAddress().getHostAddress())
                .put("network.publish_host", FBUtilities.getBroadcastRpcAddress().getHostAddress())
                .put("transport.bind_host", FBUtilities.getLocalAddress().getHostAddress())
                .put("transport.publish_host", Boolean.getBoolean("es.use_internal_address") ? FBUtilities.getLocalAddress().getHostAddress() : FBUtilities.getBroadcastAddress().getHostAddress())
                .put("path.data", getElasticsearchDataDir())
                .put(settings)
                 // not overloadable settings.
                .put("discovery.type", "cassandra")
                .put("node.data", true)
                .put("node.master", true)
                .put("node.name", CassandraDiscovery.buildNodeName(FBUtilities.getBroadcastAddress()))
                .put("node.attr.dc", DatabaseDescriptor.getLocalDataCenter())
                .put("node.attr.rack", DatabaseDescriptor.getEndpointSnitch().getRack(FBUtilities.getBroadcastAddress()))
                .put("cluster.name", ClusterService.getElasticsearchClusterName(env.settings()))
                .build();
    }
    
    public static Client client() {
        if ((instance.node != null) && (!instance.node.isClosed()))
            return instance.node.client();
        return null;
    }

    public static Injector injector() {
        if ((instance.node != null) && (!instance.node.isClosed()))
            return instance.node.injector();
        return null;
    }

    public static String getHomeDir() {
        String cassandra_home = System.getenv("CASSANDRA_HOME");
        if (cassandra_home == null) {
            cassandra_home = System.getProperty("cassandra.home", System.getProperty("path.home"));
            if (cassandra_home == null)
                throw new IllegalStateException("Cannot start, environnement variable CASSANDRA_HOME and system properties cassandra.home or path.home are null. Please set one of these to start properly.");
        }
        return cassandra_home;
    }
    
    public static String getConfigDir() {
        String cassandra_conf = System.getenv("CASSANDRA_CONF");
        if (cassandra_conf == null) {
            cassandra_conf = System.getProperty("cassandra.conf", System.getProperty("path.conf",getHomeDir()+"/conf"));
        }
        return cassandra_conf;
    }
    
    public static String getElasticsearchDataDir() {
        String cassandra_storage = System.getProperty("cassandra.storagedir", getHomeDir() + File.separator + "data");
        return cassandra_storage + File.separator + "elasticsearch.data";
    }
    
    public static void main(String[] args) {
        try
        {
            DatabaseDescriptor.daemonInitialization();
            DatabaseDescriptor.createAllDirectories();
        }
        catch (ExceptionInInitializerError e)
        {
            System.out.println("Exception (" + e.getClass().getName() + ") encountered during startup: " + e.getMessage());
            String errorMessage = buildErrorMessage("Initialization", e);
            System.err.println(errorMessage);
            System.err.flush();
            System.exit(3);
        }
        
        boolean foreground = System.getProperty("cassandra-foreground") != null;
        // handle the wrapper system property, if its a service, don't run as a
        // service
        if (System.getProperty("wrapper.service", "XXX").equalsIgnoreCase("true")) {
            foreground = false;
        }
        
        if (System.getProperty("es.max-open-files", "false").equals("true")) {
            Logger logger = Loggers.getLogger(Bootstrap.class);
            logger.info("max_open_files [{}]", ProcessProbe.getInstance().getMaxFileDescriptorCount());
        }

        // warn if running using the client VM
        if (JvmInfo.jvmInfo().getVmName().toLowerCase(Locale.ROOT).contains("client")) {
            Logger logger = Loggers.getLogger(Bootstrap.class);
            logger.warn("jvm uses the client vm, make sure to run `java` with the server vm for best performance by adding `-server` to the command line");
        }
        
        // disable netty setAvailableProcessors by Elasticsearch (set by Cassandra).
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                System.setProperty("es.set.netty.runtime.available.processors", "false");
                return null;
            }
        });
        
        String stage = "Initialization";

        try {
            if (!foreground) {
                System.out.close();
            }
            instance = new ElassandraDaemon(InternalSettingsPreparer.prepareEnvironment(
                    Settings.builder()
                    .put("node.name","node0")
                    .put("path.home",getHomeDir())
                    .build(),
                    foreground ? Terminal.DEFAULT : null,
                    Collections.EMPTY_MAP, 
                    Paths.get(getConfigDir())));
            
            instance.activate(true, true, instance.env.settings(), instance.env,  Collections.<Class<? extends Plugin>>emptyList());
            if (!foreground) {
                System.err.close();
            }

            keepAliveLatch = new CountDownLatch(1);
            // keep this thread alive (non daemon thread) until we shutdown
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    keepAliveLatch.countDown();
                }
            });

            keepAliveThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        keepAliveLatch.await();
                    } catch (InterruptedException e) {
                        // bail out
                    }
                }
            }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
            keepAliveThread.setDaemon(false);
            keepAliveThread.start();
        } catch (Throwable e) {
            Logger logger = Loggers.getLogger(ElassandraDaemon.class);
            if (instance.node != null) {
                logger = Loggers.getLogger(ElassandraDaemon.class, instance.node.settings().get("name"));
            }
            String errorMessage = buildErrorMessage(stage, e);
            if (foreground) {
                System.err.println(errorMessage);
                System.err.flush();
                //Loggers.disableConsoleLogging();
            }
            logger.error("Exception", e);

            System.exit(3);
        }
    }

    private static String buildErrorMessage(String stage, Throwable e) {
        StringBuilder errorMessage = new StringBuilder("{").append(Version.CURRENT).append("}: ");
        errorMessage.append(stage).append(" Failed ...\n");
        if (e instanceof CreationException) {
            CreationException createException = (CreationException) e;
            Set<String> seenMessages = newHashSet();
            int counter = 1;
            for (Message message : createException.getErrorMessages()) {
                String detailedMessage;
                if (message.getCause() == null) {
                    detailedMessage = message.getMessage();
                } else {
                    detailedMessage = ExceptionsHelper.detailedMessage(message.getCause());
                }
                if (detailedMessage == null) {
                    detailedMessage = message.getMessage();
                }
                if (seenMessages.contains(detailedMessage)) {
                    continue;
                }
                seenMessages.add(detailedMessage);
                errorMessage.append("").append(counter++).append(") ").append(detailedMessage);
            }
        } else {
            errorMessage.append("- ").append(ExceptionsHelper.detailedMessage(e));
        }
        if (Loggers.getLogger(ElassandraDaemon.class).isDebugEnabled()) {
            errorMessage.append("\n").append(ExceptionsHelper.stackTrace(e));
        }
        return errorMessage.toString();
    }
}

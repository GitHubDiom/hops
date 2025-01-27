package org.apache.hadoop.hdfs;

import static org.apache.hadoop.crypto.key.KeyProvider.KeyVersion;
import static org.apache.hadoop.crypto.key.KeyProviderCryptoExtension.EncryptedKeyVersion;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_CRYPTO_CODEC_CLASSES_KEY_PREFIX;
import static org.apache.hadoop.hdfs.DFSConfigKeys.*;

import com.google.gson.JsonObject;
import io.hops.leader_election.node.SortedActiveNodeList;
import io.hops.metadata.hdfs.entity.EncodingPolicy;

import java.io.*;
import java.net.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import io.hops.metadata.hdfs.entity.MetaStatus;

import javax.net.SocketFactory;

import io.hops.metrics.TransactionEvent;
import io.hops.transaction.context.EntityContextStat;
import io.hops.transaction.context.TransactionsStats;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CipherSuite;
import org.apache.hadoop.crypto.CryptoCodec;
import org.apache.hadoop.crypto.CryptoInputStream;
import org.apache.hadoop.crypto.CryptoOutputStream;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProviderCryptoExtension;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.BlockStorageLocation;
import org.apache.hadoop.fs.CacheFlag;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.HdfsBlockLocation;
import org.apache.hadoop.fs.InvalidPathException;
import org.apache.hadoop.fs.MD5MD5CRC32CastagnoliFileChecksum;
import org.apache.hadoop.fs.MD5MD5CRC32FileChecksum;
import org.apache.hadoop.fs.MD5MD5CRC32GzipFileChecksum;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.VolumeId;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.client.HdfsDataInputStream;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveEntry;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveIterator;
import org.apache.hadoop.hdfs.protocol.CachePoolEntry;
import org.apache.hadoop.hdfs.protocol.CachePoolInfo;
import org.apache.hadoop.hdfs.protocol.CachePoolIterator;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.CorruptFileBlocks;
import org.apache.hadoop.hdfs.protocol.DSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.EncryptionZone;
import org.apache.hadoop.hdfs.protocol.EncryptionZoneIterator;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LastBlockWithStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.NSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.RollingUpgradeInfo;
import org.apache.hadoop.hdfs.protocol.UnresolvedPathException;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferProtoUtil;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.Op;
import org.apache.hadoop.hdfs.protocol.datatransfer.ReplaceDatanodeOnFailure;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.BlockOpResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.OpBlockChecksumResponseProto;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.protocolPB.PBHelper;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.datanode.CachingStrategy;
import org.apache.hadoop.hdfs.server.namenode.ServerlessNameNode;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.hdfs.protocol.HdfsBlocksMetadata;
import org.apache.hadoop.hdfs.serverless.invoking.ServerlessNameNodeClient;
import org.apache.hadoop.hdfs.serverless.invoking.ServerlessInvokerBase;
import org.apache.hadoop.hdfs.serverless.invoking.ServerlessInvokerFactory;
import io.hops.metrics.OperationPerformed;
import org.apache.hadoop.hdfs.serverless.userserver.ServerAndInvokerManager;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.retry.LossyRetryInvocationHandler;
import org.apache.hadoop.ipc.*;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenRenewer;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataChecksum.Type;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Time;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import io.hops.metadata.hdfs.entity.EncodingStatus;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FsTracer;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.hdfs.net.Peer;
import org.apache.hadoop.hdfs.net.TcpPeerServer;
import org.apache.hadoop.hdfs.protocol.datatransfer.TrustedChannelResolver;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataEncryptionKeyFactory;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataTransferSaslUtil;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.SaslDataTransferClient;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorageReport;
import org.apache.hadoop.util.Daemon;
import org.apache.htrace.core.TraceScope;
import org.apache.htrace.core.Tracer;

/********************************************************
 * DFSClient can connect to a Hadoop Filesystem and
 * perform basic file tasks.  It uses the ClientProtocol
 * to communicate with a ServerlessNameNode daemon, and connects
 * directly to DataNodes to read/write block data.
 *
 * Hadoop DFS users should obtain an instance of
 * DistributedFileSystem, which uses DFSClient to handle
 * filesystem tasks.
 *
 ********************************************************/
@InterfaceAudience.Private
public class DFSClient implements java.io.Closeable, RemotePeerFactory,
        DataEncryptionKeyFactory {
  public static final Log LOG = LogFactory.getLog(DFSClient.class);
  public static final long SERVER_DEFAULTS_VALIDITY_PERIOD = 60 * 60 * 1000L; // 1 hour
  static final int TCP_WINDOW_SIZE = 128 * 1024; // 128 KB

  /**
   * Issue HTTP requests to this to invoke serverless functions.
   */
  public String serverlessEndpoint;

  /**
   * The name of the serverless platform being used for the Serverless NameNodes.
   */
  public String serverlessPlatformName;

  private final Configuration conf;
  private final Tracer tracer;
  private final DfsClientConf dfsClientConf;
  ClientProtocol namenode;
  ClientProtocol leaderNN;
  final List<ClientProtocol> allNNs = new ArrayList<ClientProtocol>();
  /* The service used for delegation tokens */
  private Text dtService;

  private final File statisticsDirectory = getStatisticsDirectory();

  final UserGroupInformation ugi;
  volatile boolean clientRunning = true;
  volatile long lastLeaseRenewal;
  private volatile FsServerDefaults serverDefaults;
  private volatile long serverDefaultsLastUpdate;
  public final String clientName;
  private final SocketFactory socketFactory;
  final ReplaceDatanodeOnFailure dtpReplaceDatanodeOnFailure;
  final FileSystem.Statistics stats;
  private final String authority;
  private Random r = new Random();
  private SocketAddress[] localInterfaceAddrs;
  private DataEncryptionKey encryptionKey;
  final SaslDataTransferClient saslClient;
  private final CachingStrategy defaultReadCachingStrategy;
  private final CachingStrategy defaultWriteCachingStrategy;
  private final ClientContext clientContext;

  private static final DFSHedgedReadMetrics HEDGED_READ_METRIC =
          new DFSHedgedReadMetrics();
  private static ThreadPoolExecutor HEDGED_READ_THREAD_POOL;

  public DfsClientConf getConf() {
    return dfsClientConf;
  }

  Configuration getConfiguration() {
    return conf;
  }

  /**
   * A map from file names to {@link DFSOutputStream} objects
   * that are currently being written by this client.
   * Note that a file can only be written by a single client.
   */
  private final Map<Long, DFSOutputStream> filesBeingWritten
          = new HashMap<Long, DFSOutputStream>();

  /**
   * Same as this(ServerlessNameNode.getAddress(conf), conf);
   * @see #DFSClient(InetSocketAddress, Configuration)
   * @deprecated Deprecated at 0.21
   */
  @Deprecated
  public DFSClient(Configuration conf) throws IOException {
    this(ServerlessNameNode.getAddress(conf), conf);
  }

  public DFSClient(InetSocketAddress address, Configuration conf) throws IOException {
    this(address, null, conf, null);
    //this(ServerlessNameNode.getUri(address), conf);
  }

  /**
   * Same as this(nameNodeUri, conf, null);
   * @see #DFSClient(URI, Configuration, FileSystem.Statistics)
   */
  public DFSClient(URI hdfsUri, Configuration conf) throws IOException, URISyntaxException {
    // TODO: Fix this. Ordinarily it passes the HDFS URI here. Not sure if this will cause issues or not.
    this(new URI(conf.get(SERVERLESS_ENDPOINT, SERVERLESS_ENDPOINT_DEFAULT)), conf, null);
  }

  /**
   * Clear the collection of statistics packages.
   */
  public void clearStatisticsPackages() {
//    if (this.namenode instanceof ServerlessNameNodeClient) {
//      ((ServerlessNameNodeClient)this.namenode).getServerlessInvoker().getStatisticsPackages().clear();
//    }
    ServerlessInvokerBase.clearStatisticsPackages();
  }

  /**
   * Clear the mapping of transaction events.
   */
  public void clearTransactionStatistics() {
//    if (this.namenode instanceof ServerlessNameNodeClient) {
//      ((ServerlessNameNodeClient)this.namenode).getServerlessInvoker().getTransactionEvents().clear();
//    }

    ServerlessInvokerBase.clearTransactionEvents();
  }

  /**
   * Used for merging latency values in from other clients into a master client that we use for book-keeping.
   * This is primarily done using Ben's HopsFS benchmarking application.
   * @param tcpLatencies Latencies from TCP requests.
   * @param httpLatencies Latencies from HTTP requests.
   */
  public void addLatencies(double[] tcpLatencies, double[] httpLatencies) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient) namenode;
      client.addLatencies(tcpLatencies, httpLatencies);
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Used for merging latency values in from other clients into a master client that we use for book-keeping.
   * This is primarily done using Ben's HopsFS benchmarking application.
   * @param tcpLatencies Latencies from TCP requests.
   * @param httpLatencies Latencies from HTTP requests.
   */
  public void addLatencies(Collection<Double> tcpLatencies, Collection<Double>  httpLatencies) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient) namenode;
      client.addLatencies(tcpLatencies, httpLatencies);
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Clear the operations performed.
   */
  public void clearOperationsPerformed() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient) namenode;
      client.clearOperationsPerformed();
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Merge the provided map of statistics packages with our own.
   *
   * @param keepLocal If true, the local keys will be preserved. If false, the keys in the 'packages' parameter
   *                  will overwrite the local keys. (In general, keys should not be overwritten as keys are
   *                  requestId values, which are supposed to be unique.)
   */
  public synchronized void mergeStatisticsPackages(
          ConcurrentHashMap<String, TransactionsStats.ServerlessStatisticsPackage> packages, boolean keepLocal) {
    ConcurrentHashMap<String, TransactionsStats.ServerlessStatisticsPackage> merged = new ConcurrentHashMap<>();

    ConcurrentHashMap<String, TransactionsStats.ServerlessStatisticsPackage> local =
            ServerlessInvokerBase.getStatisticsPackages();

    if (keepLocal) {
      merged.putAll(packages);
      merged.putAll(local);
    } else {
      merged.putAll(local);
      merged.putAll(packages);
    }

    ServerlessInvokerBase.setStatisticsPackages(merged);
  }

  /**
   * Merge the provided map of transaction events with our own.
   *
   * @param keepLocal If true, the local keys will be preserved. If false, the keys in the 'packages' parameter
   *                  will overwrite the local keys. (In general, keys should not be overwritten as keys are
   *                  requestId values, which are supposed to be unique.)
   */
  public synchronized void mergeTransactionEvents(HashMap<String, List<TransactionEvent>> events,
                                      boolean keepLocal) {
    ConcurrentHashMap<String, List<TransactionEvent>> local =
            ServerlessInvokerBase.getTransactionEvents();

    ConcurrentHashMap<String, List<TransactionEvent>> merged =
            new ConcurrentHashMap<>();

    if (keepLocal) {
      merged.putAll(events);
      merged.putAll(local);
    } else {
      merged.putAll(local);
      merged.putAll(events);
    }

    ServerlessInvokerBase.setTransactionEvents(merged);
  }

  /**
   * Merge the provided map of transaction events with our own.
   *
   * @param keepLocal If true, the local keys will be preserved. If false, the keys in the 'packages' parameter
   *                  will overwrite the local keys. (In general, keys should not be overwritten as keys are
   *                  requestId values, which are supposed to be unique.)
   */
  public synchronized void mergeTransactionEvents(ConcurrentHashMap<String, List<TransactionEvent>> events,
                                     boolean keepLocal) {
    ConcurrentHashMap<String, List<TransactionEvent>> local =
            ServerlessInvokerBase.getTransactionEvents();

    ConcurrentHashMap<String, List<TransactionEvent>> merged =
            new ConcurrentHashMap<>();

    if (keepLocal) {
      merged.putAll(events);
      merged.putAll(local);
    } else {
      merged.putAll(local);
      merged.putAll(events);
    }

    ServerlessInvokerBase.setTransactionEvents(merged);
  }

  public void setBenchmarkModeEnabled(boolean benchmarkModeEnabled) {
    if (this.namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient) this.namenode;
      client.setBenchmarkModeEnabled(benchmarkModeEnabled);
    }
  }

  /**
   * Return the statistics packages from the invoker.
   */
  public ConcurrentHashMap<String, TransactionsStats.ServerlessStatisticsPackage> getStatisticsPackages() {
    return ServerlessInvokerBase.getStatisticsPackages();
  }

  /**
   * Return the transaction events from the invoker.
   */
  public ConcurrentHashMap<String, List<TransactionEvent>> getTransactionEvents() {
    return ServerlessInvokerBase.getTransactionEvents();
  }

  /**
   * Write the statistics packages to a file.
   * @param clearAfterWrite If true, clear the statistics packages after writing them.
   */
  public void dumpStatisticsPackages(boolean clearAfterWrite) throws IOException {
    ConcurrentHashMap<String, TransactionsStats.ServerlessStatisticsPackage> statisticsPackages =
            ServerlessInvokerBase.getStatisticsPackages();

    LOG.debug("Writing " + statisticsPackages.size() + " statistics packages to files now...");

    if (!statisticsDirectory.exists())
      statisticsDirectory.mkdirs();

    // These are for the entire operation. We aggregate everything together in one file,
    // and then we write individual files for each individual request.
    File csvFile = new File(getCSVFile() + ".csv");
    File detailedFile = new File(getStatsFile() + ".log");
    File resolvingCacheFile = new File(getResolvingCacheCSVFile() + ".csv");
    BufferedWriter csvWriter = new BufferedWriter(new FileWriter(csvFile, true));
    BufferedWriter detailedWriter = new BufferedWriter(new FileWriter(detailedFile, true));
    BufferedWriter resolvingCacheWriter = new BufferedWriter(new FileWriter(resolvingCacheFile, true));

    csvWriter.write(TransactionsStats.TransactionStat.getHeader());
    csvWriter.newLine();

    resolvingCacheWriter.write(TransactionsStats.ResolvingCacheStat.getHeader());
    resolvingCacheWriter.newLine();

    for (Map.Entry<String, TransactionsStats.ServerlessStatisticsPackage> entry : statisticsPackages.entrySet()) {
      String requestId = entry.getKey();
      TransactionsStats.ServerlessStatisticsPackage statisticsPackage = entry.getValue();

      List<TransactionsStats.TransactionStat> transactionStats = statisticsPackage.getTransactionStats();
      List<TransactionsStats.ResolvingCacheStat> resolvingCacheStats = statisticsPackage.getResolvingCacheStats();

      LOG.debug("Statistics package for request " + requestId + " has " + transactionStats.size() +
              " transaction stats and " + resolvingCacheStats.size() + " resolving cache stats.");

      // These files and buffered writers are per-request. We also have one single file for the entire operation.
      File requestCSVFile = new File(getCSVFile() + "-" + requestId + ".csv");
      File requestDetailedFile = new File(getStatsFile() + "-" + requestId + ".log");
      File requestResolvingCacheFile = new File(getResolvingCacheCSVFile() + "-" + requestId + ".csv");
      BufferedWriter requestCSVWriter = new BufferedWriter(new FileWriter(requestCSVFile, true));
      BufferedWriter requestDetailedWriter = new BufferedWriter(new FileWriter(requestDetailedFile, true));
      BufferedWriter requestResolvingCacheWriter = new BufferedWriter(new FileWriter(requestResolvingCacheFile, true));

      // Write CSV statistics.
      if(!requestCSVFile.exists()) {
        requestCSVWriter.write(TransactionsStats.TransactionStat.getHeader());
        requestCSVWriter.newLine();
      }
      for (TransactionsStats.TransactionStat stat : transactionStats) {
        requestCSVWriter.write(stat.toString());
        requestCSVWriter.newLine();

        csvWriter.write(stat.toString());
        csvWriter.newLine();
      }

      // Write the detailed statistics.
      for (TransactionsStats.TransactionStat stat : transactionStats) {
        requestDetailedWriter.write("Transaction: " + stat.getName().toString());
        requestDetailedWriter.newLine();

        detailedWriter.write("Transaction: " + stat.getName().toString());
        detailedWriter.newLine();

        if (stat.getIgnoredException() != null) {
          requestDetailedWriter.write(stat.getIgnoredException().toString());
          requestDetailedWriter.newLine();
          requestDetailedWriter.newLine();

          detailedWriter.write(stat.getIgnoredException().toString());
          detailedWriter.newLine();
          detailedWriter.newLine();
        }

        EntityContextStat.StatsAggregator txAggStat =
                new EntityContextStat.StatsAggregator();
        for (EntityContextStat contextStat : stat.getStats()) {
          requestDetailedWriter.write(contextStat.toString());

          detailedWriter.write(contextStat.toString());
        }

        requestDetailedWriter.write(txAggStat.toCSFString("Tx."));
        requestDetailedWriter.newLine();

        detailedWriter.write(txAggStat.toCSFString("Tx."));
        detailedWriter.newLine();
      }

      // Write the resolving cache statistics.
      if(!requestResolvingCacheFile.exists()) {
        requestResolvingCacheWriter.write(TransactionsStats.ResolvingCacheStat.getHeader());
        requestResolvingCacheWriter.newLine();
      }
      for(TransactionsStats.ResolvingCacheStat stat : resolvingCacheStats){
        requestResolvingCacheWriter.write(stat.toString());
        requestResolvingCacheWriter.newLine();

        resolvingCacheWriter.write(stat.toString());
        resolvingCacheWriter.newLine();
      }

      // Per-request writers get closed in the for-loop.
      requestCSVWriter.close();
      requestDetailedWriter.close();
      requestResolvingCacheWriter.close();
    } // For loop over the HashMap of requestID --> statistics package.

    // Operation-level writers get closed AFTER the for-loop.
    csvWriter.close();
    detailedWriter.close();
    resolvingCacheWriter.close();

    if (clearAfterWrite)
      clearStatisticsPackages();
  }

  private File getStatisticsDirectory() {
    String folder = new Timestamp(System.currentTimeMillis()).toString().replace(':', '-');
    return new File("./statistics/", folder + "/");
  }

  private File getStatsFile() {
    return new File(statisticsDirectory, "hops-stats");
  }

  private File getCSVFile() {
    return new File(statisticsDirectory, "hops-stats");
  }

  private File getResolvingCacheCSVFile() {
    return new File(statisticsDirectory, "hops-resolving-cache-stats");
  }

  /**
   * Same as this(nameNodeUri, null, conf, stats);
   *
   * Need to call the {@link DFSClient#initialize()} function before use.
   */
  public DFSClient(URI nameNodeUri, Configuration conf,
                   FileSystem.Statistics stats)
          throws IOException {
    boolean localMode = conf.getBoolean(SERVERLESS_LOCAL_MODE, SERVERLESS_LOCAL_MODE_DEFAULT);

    if (localMode)
      serverlessEndpoint = conf.get(SERVERLESS_ENDPOINT_LOCAL, SERVERLESS_ENDPOINT_LOCAL_DEFAULT);
    else
      serverlessEndpoint = conf.get(SERVERLESS_ENDPOINT, SERVERLESS_ENDPOINT_DEFAULT);

    serverlessPlatformName = conf.get(SERVERLESS_PLATFORM, SERVERLESS_PLATFORM_DEFAULT);

    LOG.info("Serverless endpoint: " + serverlessEndpoint);
    LOG.info("Serverless platform: " + serverlessPlatformName);

    // Copy only the required DFSClient configuration
    this.tracer = FsTracer.get(conf);
    this.dfsClientConf = new DfsClientConf(conf);
    this.conf = conf;
    this.stats = stats;
    this.socketFactory = NetUtils.getSocketFactoryFromProperty(conf,
            conf.get(DFSConfigKeys.DFS_CLIENT_XCEIVER_SOCKET_FACTORY_CLASS_KEY,
                    DFSConfigKeys.DEFAULT_DFS_CLIENT_XCEIVER_FACTORY_CLASS));
    this.dtpReplaceDatanodeOnFailure = ReplaceDatanodeOnFailure.get(conf);

    this.ugi = UserGroupInformation.getCurrentUser();

    this.authority = nameNodeUri == null? "null": nameNodeUri.getAuthority();

    String clientNamePrefix = "";
    if(dfsClientConf.getForceClientToWriteSFToDisk()){
      clientNamePrefix = "DFSClient";
    }else{
      clientNamePrefix = "HopsFS_DFSClient";
    }
    this.clientName = clientNamePrefix+ "_" + dfsClientConf.getTaskId() + "_" +
            DFSUtil.getRandom().nextInt() + "_" + Thread.currentThread().getId();
    int numResponseToDrop = conf.getInt(
            DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY,
            DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_DEFAULT);
    NameNodeProxies.ProxyAndInfo<ClientProtocol> proxyInfo = null;
    AtomicBoolean nnFallbackToSimpleAuth = new AtomicBoolean(false);
    if (numResponseToDrop > 0) {
      // This case is used for testing.
      LOG.warn(DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY
              + " is set to " + numResponseToDrop
              + ", this hacked client will proactively drop responses");
      proxyInfo = NameNodeProxies.createProxyWithLossyRetryHandler(conf,
              nameNodeUri, ClientProtocol.class, numResponseToDrop,
              nnFallbackToSimpleAuth);
    }

    // Create the ServerlessNameNodeClient instance and call then registerAndStartTcpServer()
    // so that it gets assigned a TCP/UDP server.
    this.namenode = new ServerlessNameNodeClient(conf, this);

    // this.namenode = new ServerlessNameNodeClient(conf, this);

    LOG.warn("Skipping the set-up of namenode and leaderNN variables...");
    /*if (proxyInfo != null) {
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();
      this.leaderNN = namenode; // only for testing
    } else if (rpcNamenode != null) {
      // This case is used for testing.
      Preconditions.checkArgument(nameNodeUri == null);
      this.namenode = rpcNamenode;
      this.leaderNN = rpcNamenode;
      dtService = null;
    } else {
      Preconditions.checkArgument(nameNodeUri != null,
              "null URI");
      proxyInfo = NameNodeProxies.createHopsRandomStickyProxy(conf, nameNodeUri,
              ClientProtocol.class, nnFallbackToSimpleAuth);
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();

      if(namenode != null) {
        try {
          List<ActiveNode> anns = namenode.getActiveNamenodesForClient().getSortedActiveNodes();
          leaderNN = NameNodeProxies.createHopsLeaderProxy(conf, nameNodeUri,
                  ClientProtocol.class, nnFallbackToSimpleAuth).getProxy();

          for (ActiveNode an : anns) {
            allNNs.add(NameNodeProxies.createNonHAProxy(conf, an.getRpcServerAddressForClients(),
                    ClientProtocol.class, ugi, false, nnFallbackToSimpleAuth).getProxy());
          }
        } catch (ConnectException e){
          LOG.warn("Namenode proxy is null");
          leaderNN = null;
          allNNs.clear();
        }
      }
    }*/

    // set epoch
    setClientEpoch();

    String localInterfaces[] =
            conf.getTrimmedStrings(DFSConfigKeys.DFS_CLIENT_LOCAL_INTERFACES);
    localInterfaceAddrs = getLocalInterfaceAddrs(localInterfaces);
    if (LOG.isDebugEnabled() && 0 != localInterfaces.length) {
      LOG.debug("Using local interfaces [" +
              Joiner.on(',').join(localInterfaces)+ "] with addresses [" +
              Joiner.on(',').join(localInterfaceAddrs) + "]");
    }

    Boolean readDropBehind = (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_READS) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_READS, false);
    Long readahead = (conf.get(DFS_CLIENT_CACHE_READAHEAD) == null) ?
            null : conf.getLong(DFS_CLIENT_CACHE_READAHEAD, 0);
    Boolean writeDropBehind = (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES, false);
    this.defaultReadCachingStrategy =
            new CachingStrategy(readDropBehind, readahead);
    this.defaultWriteCachingStrategy =
            new CachingStrategy(writeDropBehind, readahead);
    this.clientContext = ClientContext.get(
            conf.get(DFS_CLIENT_CONTEXT, DFS_CLIENT_CONTEXT_DEFAULT),
            dfsClientConf);

    if (dfsClientConf.getHedgedReadThreadpoolSize() > 0) {
      this.initThreadsNumForHedgedReads(dfsClientConf.getHedgedReadThreadpoolSize());
    }
    this.saslClient = new SaslDataTransferClient(
            conf, DataTransferSaslUtil.getSaslPropertiesResolver(conf),
            TrustedChannelResolver.getInstance(conf), nnFallbackToSimpleAuth);
  }

  public void initialize() throws IOException {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.registerAndStartTcpServer();
    }
  }

  /**
   * Create a new DFSClient connected to the given nameNodeUri or rpcNamenode.
   * If HA is enabled and a positive value is set for
   * {@link DFSConfigKeys#DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY} in the
   * configuration, the DFSClient will use {@link LossyRetryInvocationHandler}
   * as its RetryInvocationHandler. Otherwise one of nameNodeUri or rpcNamenode
   * must be null.
   */
  @VisibleForTesting
  public DFSClient(InetSocketAddress openWhiskEndpoint, ClientProtocol rpcNamenode,
                   Configuration conf, FileSystem.Statistics stats)
          throws IOException {
    // Copy only the required DFSClient configuration
    this.tracer = FsTracer.get(conf);
    this.dfsClientConf = new DfsClientConf(conf);
    this.conf = conf;
    this.stats = stats;
    this.socketFactory = NetUtils.getSocketFactoryFromProperty(conf,
            conf.get(DFSConfigKeys.DFS_CLIENT_XCEIVER_SOCKET_FACTORY_CLASS_KEY,
                    DFSConfigKeys.DEFAULT_DFS_CLIENT_XCEIVER_FACTORY_CLASS));
    this.dtpReplaceDatanodeOnFailure = ReplaceDatanodeOnFailure.get(conf);

    boolean localMode = conf.getBoolean(SERVERLESS_LOCAL_MODE, SERVERLESS_LOCAL_MODE_DEFAULT);

    if (localMode)
      serverlessEndpoint = conf.get(SERVERLESS_ENDPOINT_LOCAL, SERVERLESS_ENDPOINT_LOCAL_DEFAULT);
    else
      serverlessEndpoint = conf.get(SERVERLESS_ENDPOINT, SERVERLESS_ENDPOINT_DEFAULT);
    serverlessPlatformName = conf.get(SERVERLESS_PLATFORM, SERVERLESS_PLATFORM_DEFAULT);

    LOG.info("Serverless endpoint: " + serverlessEndpoint);
    LOG.info("Serverless platform: " + serverlessPlatformName);

    this.ugi = UserGroupInformation.getCurrentUser();

    URI nameNodeUri = ServerlessNameNode.getUri(openWhiskEndpoint);

    //System.out.println("DFSClient Constructor #1");

    this.authority = nameNodeUri == null? "null": nameNodeUri.getAuthority();

    String clientNamePrefix = "";
    if(dfsClientConf.getForceClientToWriteSFToDisk()){
      clientNamePrefix = "DFSClient";
    }else{
      clientNamePrefix = "HopsFS_DFSClient";
    }
    this.clientName = clientNamePrefix+ "_" + dfsClientConf.getTaskId() + "_" +
            DFSUtil.getRandom().nextInt() + "_" + Thread.currentThread().getId();
    int numResponseToDrop = conf.getInt(
            DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY,
            DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_DEFAULT);
    NameNodeProxies.ProxyAndInfo<ClientProtocol> proxyInfo = null;
    AtomicBoolean nnFallbackToSimpleAuth = new AtomicBoolean(false);
    if (numResponseToDrop > 0) {
      // This case is used for testing.
      LOG.warn(DFSConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY
              + " is set to " + numResponseToDrop
              + ", this hacked client will proactively drop responses");
      proxyInfo = NameNodeProxies.createProxyWithLossyRetryHandler(conf,
              nameNodeUri, ClientProtocol.class, numResponseToDrop,
              nnFallbackToSimpleAuth);
    }

    //System.out.println("DFSClient Constructor #2");

    /*if (proxyInfo != null) {
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();
      this.leaderNN = namenode; // only for testing
    } else if (rpcNamenode != null) {
      // This case is used for testing.
      Preconditions.checkArgument(nameNodeUri == null);
      this.namenode = rpcNamenode;
      this.leaderNN = rpcNamenode;
      dtService = null;
    } else {*/
      // Since our NameNodes are serverless, we do not need to create a connection to the name node.

            /*Preconditions.checkArgument(nameNodeUri != null,
                    "null URI");
            proxyInfo = NameNodeProxies.createHopsRandomStickyProxy(conf, nameNodeUri,
                    ClientProtocol.class, nnFallbackToSimpleAuth);
            this.dtService = proxyInfo.getDelegationTokenService();
            this.namenode = proxyInfo.getProxy();*/

            /*if(namenode != null) {
                try {
                    List<ActiveNode> anns = namenode.getActiveNamenodesForClient().getSortedActiveNodes();
                    leaderNN = NameNodeProxies.createHopsLeaderProxy(conf, nameNodeUri,
                            ClientProtocol.class, nnFallbackToSimpleAuth).getProxy();

                    for (ActiveNode an : anns) {
                        allNNs.add(NameNodeProxies.createNonHAProxy(conf, an.getRpcServerAddressForClients(),
                                ClientProtocol.class, ugi, false, nnFallbackToSimpleAuth).getProxy());
                    }
                } catch (ConnectException e){
                    LOG.warn("Namenode proxy is null");
                    leaderNN = null;
                    allNNs.clear();
                }
            }*/
    //}

    // set epoch
    setClientEpoch();

    String localInterfaces[] =
            conf.getTrimmedStrings(DFSConfigKeys.DFS_CLIENT_LOCAL_INTERFACES);
    localInterfaceAddrs = getLocalInterfaceAddrs(localInterfaces);
    if (LOG.isDebugEnabled() && 0 != localInterfaces.length) {
      LOG.debug("Using local interfaces [" +
              Joiner.on(',').join(localInterfaces)+ "] with addresses [" +
              Joiner.on(',').join(localInterfaceAddrs) + "]");
    }

    Boolean readDropBehind = (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_READS) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_READS, false);
    Long readahead = (conf.get(DFS_CLIENT_CACHE_READAHEAD) == null) ?
            null : conf.getLong(DFS_CLIENT_CACHE_READAHEAD, 0);
    Boolean writeDropBehind = (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES, false);
    this.defaultReadCachingStrategy =
            new CachingStrategy(readDropBehind, readahead);
    this.defaultWriteCachingStrategy =
            new CachingStrategy(writeDropBehind, readahead);
    this.clientContext = ClientContext.get(
            conf.get(DFS_CLIENT_CONTEXT, DFS_CLIENT_CONTEXT_DEFAULT),
            dfsClientConf);

    if (dfsClientConf.getHedgedReadThreadpoolSize() > 0) {
      this.initThreadsNumForHedgedReads(dfsClientConf.getHedgedReadThreadpoolSize());
    }
    this.saslClient = new SaslDataTransferClient(
            conf, DataTransferSaslUtil.getSaslPropertiesResolver(conf),
            TrustedChannelResolver.getInstance(conf), nnFallbackToSimpleAuth);
  }

  /**
   * Return the socket addresses to use with each configured
   * local interface. Local interfaces may be specified by IP
   * address, IP address range using CIDR notation, interface
   * name (e.g. eth0) or sub-interface name (e.g. eth0:0).
   * The socket addresses consist of the IPs for the interfaces
   * and the ephemeral port (port 0). If an IP, IP range, or
   * interface name matches an interface with sub-interfaces
   * only the IP of the interface is used. Sub-interfaces can
   * be used by specifying them explicitly (by IP or name).
   *
   * @return SocketAddresses for the configured local interfaces,
   *    or an empty array if none are configured
   * @throws UnknownHostException if a given interface name is invalid
   */
  private static SocketAddress[] getLocalInterfaceAddrs(
          String interfaceNames[]) throws UnknownHostException {
    List<SocketAddress> localAddrs = new ArrayList<SocketAddress>();
    for (String interfaceName : interfaceNames) {
      if (InetAddresses.isInetAddress(interfaceName)) {
        localAddrs.add(new InetSocketAddress(interfaceName, 0));
      } else if (NetUtils.isValidSubnet(interfaceName)) {
        for (InetAddress addr : NetUtils.getIPs(interfaceName, false)) {
          localAddrs.add(new InetSocketAddress(addr, 0));
        }
      } else {
        for (String ip : DNS.getIPs(interfaceName, false)) {
          localAddrs.add(new InetSocketAddress(ip, 0));
        }
      }
    }
    return localAddrs.toArray(new SocketAddress[localAddrs.size()]);
  }

  /**
   * Select one of the configured local interfaces at random. We use a random
   * interface because other policies like round-robin are less effective
   * given that we cache connections to datanodes.
   *
   * @return one of the local interface addresses at random, or null if no
   *    local interfaces are configured
   */
  SocketAddress getRandomLocalInterfaceAddr() {
    if (localInterfaceAddrs.length == 0) {
      return null;
    }
    final int idx = r.nextInt(localInterfaceAddrs.length);
    final SocketAddress addr = localInterfaceAddrs[idx];
    if (LOG.isDebugEnabled()) {
      LOG.debug("Using local interface " + addr);
    }
    return addr;
  }

  public void setConsistencyProtocolEnabled(boolean enabled) {
    if (this.namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)this.namenode;
      client.setConsistencyProtocolEnabled(enabled);
    } else {
      LOG.warn("Internal NameNode API is not an instance of 'ServerlessNameNodeClient'. " +
              "Cannot modify consistency protocol flag.");
    }
  }

  public void setServerlessFunctionLogLevel(String logLevel) {
    if (this.namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)this.namenode;
      client.setServerlessFunctionLogLevel(logLevel);
    } else {
      LOG.warn("Internal NameNode API is not an instance of 'ServerlessNameNodeClient'. " +
              "Cannot modify log4j log level parameter.");
    }
  }

  public boolean getConsistencyProtocolEnabled() {
    if (this.namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)this.namenode;
      return client.getConsistencyProtocolEnabled();
    } else {
      throw new IllegalStateException("Internal NameNode API is not an instance of 'ServerlessNameNodeClient'. " +
              "Cannot return consistency protocol flag.");
    }
  }

  public String getServerlessFunctionLogLevel() {
    if (this.namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)this.namenode;
      return client.getServerlessFunctionLogLevel();
    } else {
      throw new IllegalStateException("Internal NameNode API is not an instance of 'ServerlessNameNodeClient'. " +
              "Cannot return log4j log level parameter.");
    }
  }

  /**
   * Return the timeout that clients should use when writing to datanodes.
   * @param numNodes the number of nodes in the pipeline.
   */
  int getDatanodeWriteTimeout(int numNodes) {
    final int t = dfsClientConf.getDatanodeSocketWriteTimeout();
    return t > 0? t + HdfsServerConstants.WRITE_TIMEOUT_EXTENSION*numNodes: 0;
  }

  int getDatanodeReadTimeout(int numNodes) {
    final int t = dfsClientConf.getSocketTimeout();
    return t > 0? HdfsServerConstants.READ_TIMEOUT_EXTENSION*numNodes + t: 0;
  }

  @VisibleForTesting
  public String getClientName() {
    return clientName;
  }

  void checkOpen() throws IOException {
    if (!clientRunning) {
      IOException result = new IOException("Filesystem closed");
      throw result;
    }
  }

  /** Return the lease renewer instance. The renewer thread won't start
   *  until the first output stream is created. The same instance will
   *  be returned until all output streams are closed.
   */
  public LeaseRenewer getLeaseRenewer() throws IOException {
    return LeaseRenewer.getInstance(authority, ugi, this);
  }

  /** Get a lease and start automatic renewal */
  private void beginFileLease(final long inodeId, final DFSOutputStream out)
          throws IOException {
    getLeaseRenewer().put(inodeId, out, this);
  }

  /** Stop renewal of lease for the file. */
  void endFileLease(final long inodeId) throws IOException {
    getLeaseRenewer().closeFile(inodeId, this);
  }


  /** Put a file. Only called from LeaseRenewer, where proper locking is
   *  enforced to consistently update its local dfsclients array and
   *  client's filesBeingWritten map.
   */
  void putFileBeingWritten(final long inodeId, final DFSOutputStream out) {
    synchronized(filesBeingWritten) {
      filesBeingWritten.put(inodeId, out);
      // update the last lease renewal time only when there was no
      // writes. once there is one write stream open, the lease renewer
      // thread keeps it updated well with in anyone's expiration time.
      if (lastLeaseRenewal == 0) {
        updateLastLeaseRenewal();
      }
    }
  }

  /** Remove a file. Only called from LeaseRenewer. */
  void removeFileBeingWritten(final  long inodeId) {
    synchronized(filesBeingWritten) {
      filesBeingWritten.remove(inodeId);
      if (filesBeingWritten.isEmpty()) {
        lastLeaseRenewal = 0;
      }
    }
  }

  /** Is file-being-written map empty? */
  boolean isFilesBeingWrittenEmpty() {
    synchronized(filesBeingWritten) {
      return filesBeingWritten.isEmpty();
    }
  }

  /** @return true if the client is running */
  boolean isClientRunning() {
    return clientRunning;
  }

  long getLastLeaseRenewal() {
    return lastLeaseRenewal;
  }

  void updateLastLeaseRenewal() {
    synchronized(filesBeingWritten) {
      if (filesBeingWritten.isEmpty()) {
        return;
      }
      lastLeaseRenewal = Time.monotonicNow();
    }
  }

  /**
   * Renew leases.
   * @return true if lease was renewed. May return false if this
   * client has been closed or has no files open.
   **/
  boolean renewLease() throws IOException {
    if (clientRunning && !isFilesBeingWrittenEmpty()) {
      try {
        namenode.renewLease(clientName);
        updateLastLeaseRenewal();
        return true;
      } catch (IOException e) {
        // Abort if the lease has already expired.
        final long elapsed = Time.monotonicNow() - getLastLeaseRenewal();
        if (elapsed > HdfsConstants.LEASE_HARDLIMIT_PERIOD) {
          LOG.warn("Failed to renew lease for " + clientName + " for "
                  + (elapsed/1000) + " seconds (>= hard-limit ="
                  + (HdfsConstants.LEASE_HARDLIMIT_PERIOD/1000) + " seconds.) "
                  + "Closing all files being written ...", e);
          closeAllFilesBeingWritten(true);
        } else {
          // Let the lease renewer handle it and retry.
          throw e;
        }
      }
    }
    return false;
  }

  /**
   * Close connections the Namenode.
   */
  void closeConnectionsToNamenodes() {
//    LOG.warn("There are no long-running Name Node connections to close because this version of HopsFS uses " +
//            "serverless Name Nodes!");
    /*if(leaderNN == namenode){
      //close only one
      stopProxy(namenode);
    }else{
      stopProxy(namenode);
      stopProxy(leaderNN);
    }
    for(ClientProtocol nn : allNNs){
      stopProxy(nn);
    }*/
  }

  void stopProxy(ClientProtocol proxy){
    if(proxy != null){
      RPC.stopProxy(proxy);
    }
  }

  /**
   * Ping a particular serverless NameNode deployment (i.e., invoke a NameNode from the specified deployment).
   * @param targetDeployment The deployment from which a NameNode will be invoked.
   */
  public void ping(int targetDeployment) throws IOException {
    this.namenode.ping(targetDeployment);
  }

  /**
   * Attempt to pre-warm the NameNodes by pinging each deployment the specified number of times.
   * @param numPingsPerThread Number of times to ping the deployment.
   * @param numThreadsPerDeployment Number of threads to use when pinging each deployment.
   */
  public void prewarm(int numPingsPerThread, int numThreadsPerDeployment) throws IOException {
    this.namenode.prewarm(numPingsPerThread, numThreadsPerDeployment);
  }

  /** Abort and release resources held.  Ignore all errors. */
  void abort() {
    clientRunning = false;
    closeAllFilesBeingWritten(true);
    try {
      // remove reference to this client and stop the renewer,
      // if there is no more clients under the renewer.
      getLeaseRenewer().closeClient(this);
    } catch (IOException ioe) {
      LOG.info("Exception occurred while aborting the client " + ioe);
    }
    closeConnectionsToNamenodes();
  }

  /** Close/abort all files being written. */
  private void closeAllFilesBeingWritten(final boolean abort) {
    for(;;) {
      final long inodeId;
      final DFSOutputStream out;
      synchronized(filesBeingWritten) {
        if (filesBeingWritten.isEmpty()) {
          return;
        }
        inodeId = filesBeingWritten.keySet().iterator().next();
        out = filesBeingWritten.remove(inodeId);
      }
      if (out != null) {
        try {
          if (abort) {
            out.abort();
          } else {
            out.close();
          }
        } catch(IOException ie) {
          LOG.error("Failed to " + (abort? "abort": "close") +
                  " inode " + inodeId, ie);
        }
      }
    }
  }

  /**
   * Close the file system, abandoning all of the leases and files being
   * created and close connections to the namenode.
   */
  @Override
  public synchronized void close() throws IOException {
    if(clientRunning) {
      closeAllFilesBeingWritten(false);
      clientRunning = false;
      getLeaseRenewer().closeClient(this);

      // close connections to the namenode
      closeConnectionsToNamenodes();

      // Stop the ServerlessNameNodeClient if that is what we're using as our ClientAPI name node.
      if (this.namenode instanceof ServerlessNameNodeClient) {
        ServerlessNameNodeClient serverlessNameNodeClient = (ServerlessNameNodeClient)this.namenode;
        serverlessNameNodeClient.stop();
      }
    }
  }

  /**
   * This should be called in place of calling the namenode's addBlock() operation directly. This will invoke
   * a serverless NameNode and perform the addBlock() operation.
   */
  public LocatedBlock addBlock(String src, String clientName,
                               ExtendedBlock previous, DatanodeInfo[] excludeNodes,
                               long fileId, String[] favoredNodes) throws IOException, ClassNotFoundException {
    return this.namenode.addBlock(src, clientName, previous, excludeNodes, fileId, favoredNodes);
  }

  /**
   * Close all open streams, abandoning all of the leases and files being
   * created.
   * @param abort whether streams should be gracefully closed
   */
  public void closeOutputStreams(boolean abort) {
    if (clientRunning) {
      closeAllFilesBeingWritten(abort);
    }
  }


  /**
   * @see ClientProtocol#getPreferredBlockSize(String)
   */
  public long getBlockSize(String f) throws IOException {
    try (TraceScope ignored = newPathTraceScope("getBlockSize", f)) {
      return namenode.getPreferredBlockSize(f);
    } catch (IOException ie) {
      LOG.warn("Problem getting block size", ie);
      throw ie;
    }
  }

  /**
   * Get server default values for a number of configuration params.
   * @see ClientProtocol#getServerDefaults()
   */
  public FsServerDefaults getServerDefaults() throws IOException {
    long now = Time.monotonicNow();
    if ((serverDefaults == null) ||
            (now - serverDefaultsLastUpdate > SERVER_DEFAULTS_VALIDITY_PERIOD)) {
      serverDefaults = namenode.getServerDefaults();
      serverDefaultsLastUpdate = now;
    }
    assert serverDefaults != null;
    return serverDefaults;
  }

  /**
   * Get a canonical token service name for this client's tokens.  Null should
   * be returned if the client is not using tokens.
   * @return the token service for the client
   */
  @InterfaceAudience.LimitedPrivate( { "HDFS" })
  public String getCanonicalServiceName() {
    return (dtService != null) ? dtService.toString() : null;
  }

  /**
   * @see ClientProtocol#getDelegationToken(Text)
   */
  public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
          throws IOException {
    assert dtService != null;
    try (TraceScope ignored = tracer.newScope("getDelegationToken")) {
      Token<DelegationTokenIdentifier> token =
              namenode.getDelegationToken(renewer);
      if (token != null) {
        token.setService(this.dtService);
        LOG.info("Created " + DelegationTokenIdentifier.stringifyToken(token));
      } else {
        LOG.info("Cannot get delegation token from " + renewer);
      }
      return token;
    }
  }

  /**
   * Renew a delegation token
   * @param token the token to renew
   * @return the new expiration time
   * @throws InvalidToken
   * @throws IOException
   * @deprecated Use Token.renew instead.
   */
  @Deprecated
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
          throws InvalidToken, IOException {
    LOG.info("Renewing " + DelegationTokenIdentifier.stringifyToken(token));
    try {
      return token.renew(conf);
    } catch (InterruptedException ie) {
      throw new RuntimeException("caught interrupted", ie);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(InvalidToken.class,
              AccessControlException.class);
    }
  }

  private static final Map<String, Boolean> localAddrMap =
          Collections.synchronizedMap(new HashMap<>());

  public static boolean isLocalAddress(InetSocketAddress targetAddr) {
    InetAddress addr = targetAddr.getAddress();
    Boolean cached = localAddrMap.get(addr.getHostAddress());
    if (cached != null) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Address " + targetAddr +
                (cached ? " is local" : " is not local"));
      }
      return cached;
    }

    boolean local = NetUtils.isLocalAddress(addr);

    if (LOG.isTraceEnabled()) {
      LOG.trace("Address " + targetAddr +
              (local ? " is local" : " is not local"));
    }
    localAddrMap.put(addr.getHostAddress(), local);
    return local;
  }

  /**
   * Cancel a delegation token
   * @param token the token to cancel
   * @throws InvalidToken
   * @throws IOException
   * @deprecated Use Token.cancel instead.
   */
  @Deprecated
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
          throws InvalidToken, IOException {
    LOG.info("Cancelling " + DelegationTokenIdentifier.stringifyToken(token));
    try {
      token.cancel(conf);
    } catch (InterruptedException ie) {
      throw new RuntimeException("caught interrupted", ie);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(InvalidToken.class,
              AccessControlException.class);
    }
  }

  @InterfaceAudience.Private
  public static class Renewer extends TokenRenewer {

    static {
      //Ensure that HDFS Configuration files are loaded before trying to use
      // the renewer.
      HdfsConfiguration.init();
    }

    @Override
    public boolean handleKind(Text kind) {
      return DelegationTokenIdentifier.HDFS_DELEGATION_KIND.equals(kind);
    }

    @SuppressWarnings("unchecked")
    @Override
    public long renew(Token<?> token, Configuration conf) throws IOException {
      Token<DelegationTokenIdentifier> delToken =
              (Token<DelegationTokenIdentifier>) token;
      ClientProtocol nn = getNNProxy(delToken, conf);
      try {
        return nn.renewDelegationToken(delToken);
      } catch (RemoteException re) {
        throw re.unwrapRemoteException(InvalidToken.class,
                AccessControlException.class);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void cancel(Token<?> token, Configuration conf) throws IOException {
      Token<DelegationTokenIdentifier> delToken =
              (Token<DelegationTokenIdentifier>) token;
      LOG.info("Cancelling " +
              DelegationTokenIdentifier.stringifyToken(delToken));
      ClientProtocol nn = getNNProxy(delToken, conf);
      try {
        nn.cancelDelegationToken(delToken);
      } catch (RemoteException re) {
        throw re.unwrapRemoteException(InvalidToken.class,
                AccessControlException.class);
      }
    }

    private static ClientProtocol getNNProxy(
            Token<DelegationTokenIdentifier> token, Configuration conf)
            throws IOException {
      URI uri = HAUtilClient.getServiceUriFromToken(
              HdfsConstants.HDFS_URI_SCHEME, token);
      if (HAUtilClient.isTokenForLogicalUri(token) &&
              !HAUtilClient.isLogicalUri(conf, uri)) {
        // If the token is for a logical nameservice, but the configuration
        // we have disagrees about that, we can't actually renew it.
        // This can be the case in MR, for example, if the RM doesn't
        // have all of the HA clusters configured in its configuration.
        throw new IOException("Unable to map logical nameservice URI '" +
                uri + "' to a ServerlessNameNode. Local configuration does not have " +
                "a failover proxy provider configured.");
      }

      NameNodeProxies.ProxyAndInfo<ClientProtocol> info =
              NameNodeProxies.createProxy(conf, uri, ClientProtocol.class);
      assert info.getDelegationTokenService().equals(token.getService()) :
              "Returned service '" + info.getDelegationTokenService().toString() +
                      "' doesn't match expected service '" +
                      token.getService().toString() + "'";

      return info.getProxy();
    }

    @Override
    public boolean isManaged(Token<?> token) throws IOException {
      return true;
    }

  }

  /**
   * Report corrupt blocks that were discovered by the client.
   * @see ClientProtocol#reportBadBlocks(LocatedBlock[])
   */
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    namenode.reportBadBlocks(blocks);
  }

  public LocatedBlocks getLocatedBlocks(String src, long start)
          throws IOException {
    return getLocatedBlocks(src, start, dfsClientConf.getPrefetchSize());
  }

  /*
   * This is just a wrapper around callGetBlockLocations, but non-static so that
   * we can stub it out for tests.
   */
  @VisibleForTesting
  public LocatedBlocks getLocatedBlocks(String src, long start, long length)
          throws IOException {
    try (TraceScope ignored = newPathTraceScope("getBlockLocations", src)) {
      return callGetBlockLocations(namenode, src, start, length);
    }
  }

  /**
   * @see ClientProtocol#getBlockLocations(String, long, long)
   */
  static LocatedBlocks callGetBlockLocations(ClientProtocol namenode,
                                             String src, long start, long length)
          throws IOException {
    try {
      return namenode.getBlockLocations(src, start, length);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Recover a file's lease
   * @param src a file's path
   * @return true if the file is already closed
   * @throws IOException
   */
  boolean recoverLease(String src) throws IOException {
    checkOpen();

    try (TraceScope ignored = newPathTraceScope("recoverLease", src)) {
      return namenode.recoverLease(src, clientName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(FileNotFoundException.class,
              AccessControlException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Get block location info about file
   *
   * getBlockLocations() returns a list of hostnames that store
   * data for a specific file region.  It returns a set of hostnames
   * for every block within the indicated region.
   *
   * This function is very useful when writing code that considers
   * data-placement when performing operations.  For example, the
   * MapReduce system tries to schedule tasks on the same machines
   * as the data-block the task processes.
   */
  public BlockLocation[] getBlockLocations(String src, long start,
                                           long length) throws IOException, UnresolvedLinkException {
    try (TraceScope ignored = newPathTraceScope("getBlockLocations", src)) {
      LocatedBlocks blocks = getLocatedBlocks(src, start, length);
      BlockLocation[] locations =  DFSUtilClient.locatedBlocks2Locations(blocks);
      HdfsBlockLocation[] hdfsLocations = new HdfsBlockLocation[locations.length];
      for (int i = 0; i < locations.length; i++) {
        hdfsLocations[i] = new HdfsBlockLocation(locations[i], blocks.get(i));
      }
      return hdfsLocations;
    }
  }

  /**
   * Get block location information about a list of {@link HdfsBlockLocation}.
   * Used by DistributedFileSystem.getFileBlockStorageLocations(List) to
   * get {@link BlockStorageLocation}s for blocks returned by
   * {@link DistributedFileSystem#getFileBlockLocations(org.apache.hadoop.fs.FileStatus, long, long)}
   * .
   *
   * This is done by making a round of RPCs to the associated datanodes, asking
   * the volume of each block replica. The returned array of
   * {@link BlockStorageLocation} expose this information as a
   * {@link VolumeId}.
   *
   * @param blockLocations
   *          target blocks on which to query volume location information
   * @return volumeBlockLocations original block array augmented with additional
   *         volume location information for each replica.
   */
  public BlockStorageLocation[] getBlockStorageLocations(
          List<BlockLocation> blockLocations) throws IOException,
          UnsupportedOperationException, InvalidBlockTokenException {
    if (!getConf().isHdfsBlocksMetadataEnabled()) {
      throw new UnsupportedOperationException("Datanode-side support for " +
              "getVolumeBlockLocations() must also be enabled in the client " +
              "configuration.");
    }
    // Downcast blockLocations and fetch out required LocatedBlock(s)
    List<LocatedBlock> blocks = new ArrayList<LocatedBlock>();
    for (BlockLocation loc : blockLocations) {
      if (!(loc instanceof HdfsBlockLocation)) {
        throw new ClassCastException("DFSClient#getVolumeBlockLocations " +
                "expected to be passed HdfsBlockLocations");
      }
      HdfsBlockLocation hdfsLoc = (HdfsBlockLocation) loc;
      blocks.add(hdfsLoc.getLocatedBlock());
    }

    // Re-group the LocatedBlocks to be grouped by datanodes, with the values
    // a list of the LocatedBlocks on the datanode.
    Map<DatanodeInfo, List<LocatedBlock>> datanodeBlocks =
            new LinkedHashMap<DatanodeInfo, List<LocatedBlock>>();
    for (LocatedBlock b : blocks) {
      for (DatanodeInfo info : b.getLocations()) {
        if (!datanodeBlocks.containsKey(info)) {
          datanodeBlocks.put(info, new ArrayList<LocatedBlock>());
        }
        List<LocatedBlock> l = datanodeBlocks.get(info);
        l.add(b);
      }
    }

    // Make RPCs to the datanodes to get volume locations for its replicas
    TraceScope scope =
            tracer.newScope("getBlockStorageLocations");
    Map<DatanodeInfo, HdfsBlocksMetadata> metadatas;
    try {
      metadatas = BlockStorageLocationUtil.
              queryDatanodesForHdfsBlocksMetadata(conf, datanodeBlocks,
                      getConf().getFileBlockStorageLocationsNumThreads(),
                      getConf().getFileBlockStorageLocationsTimeoutMs(),
                      getConf().isConnectToDnViaHostname(), tracer, scope.getSpanId());
      if (LOG.isTraceEnabled()) {
        LOG.trace("metadata returned: "
                + Joiner.on("\n").withKeyValueSeparator("=").join(metadatas));
      }
    } finally {
      scope.close();
    }

    // Regroup the returned VolumeId metadata to again be grouped by
    // LocatedBlock rather than by datanode
    Map<LocatedBlock, List<VolumeId>> blockVolumeIds = BlockStorageLocationUtil
            .associateVolumeIdsWithBlocks(blocks, metadatas);

    // Combine original BlockLocations with new VolumeId information
    BlockStorageLocation[] volumeBlockLocations = BlockStorageLocationUtil
            .convertToVolumeBlockLocations(blocks, blockVolumeIds);

    return volumeBlockLocations;
  }

  /**
   * Decrypts a EDEK by consulting the KeyProvider.
   */
  private KeyVersion decryptEncryptedDataEncryptionKey(FileEncryptionInfo
                                                               feInfo) throws IOException {
    KeyProvider provider = getKeyProvider();
    if (provider == null) {
      throw new IOException("No KeyProvider is configured, cannot access" +
              " an encrypted file");
    }
    EncryptedKeyVersion ekv = EncryptedKeyVersion.createForDecryption(
            feInfo.getKeyName(), feInfo.getEzKeyVersionName(), feInfo.getIV(),
            feInfo.getEncryptedDataEncryptionKey());
    try {
      KeyProviderCryptoExtension cryptoProvider = KeyProviderCryptoExtension
              .createKeyProviderCryptoExtension(provider);
      return cryptoProvider.decryptEncryptedKey(ekv);
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
  }

  /**
   * Obtain the crypto protocol version from the provided FileEncryptionInfo,
   * checking to see if this version is supported by.
   *
   * @param feInfo FileEncryptionInfo
   * @return CryptoProtocolVersion from the feInfo
   * @throws IOException if the protocol version is unsupported.
   */
  private static CryptoProtocolVersion getCryptoProtocolVersion
  (FileEncryptionInfo feInfo) throws IOException {
    final CryptoProtocolVersion version = feInfo.getCryptoProtocolVersion();
    if (!CryptoProtocolVersion.supports(version)) {
      throw new IOException("Client does not support specified " +
              "CryptoProtocolVersion " + version.getDescription() + " version " +
              "number" + version.getVersion());
    }
    return version;
  }

  /**
   * Obtain a CryptoCodec based on the CipherSuite set in a FileEncryptionInfo
   * and the available CryptoCodecs configured in the Configuration.
   *
   * @param conf   Configuration
   * @param feInfo FileEncryptionInfo
   * @return CryptoCodec
   * @throws IOException if no suitable CryptoCodec for the CipherSuite is
   *                     available.
   */
  private static CryptoCodec getCryptoCodec(Configuration conf,
                                            FileEncryptionInfo feInfo) throws IOException {
    final CipherSuite suite = feInfo.getCipherSuite();
    if (suite.equals(CipherSuite.UNKNOWN)) {
      throw new IOException("ServerlessNameNode specified unknown CipherSuite with ID "
              + suite.getUnknownValue() + ", cannot instantiate CryptoCodec.");
    }
    final CryptoCodec codec = CryptoCodec.getInstance(conf, suite);
    if (codec == null) {
      throw new UnknownCipherSuiteException(
              "No configuration found for the cipher suite "
                      + suite.getConfigSuffix() + " prefixed with "
                      + HADOOP_SECURITY_CRYPTO_CODEC_CLASSES_KEY_PREFIX
                      + ". Please see the example configuration "
                      + "hadoop.security.crypto.codec.classes.EXAMPLECIPHERSUITE "
                      + "at core-default.xml for details.");
    }
    return codec;
  }

  /**
   * Wraps the stream in a CryptoInputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataInputStream createWrappedInputStream(DFSInputStream dfsis)
          throws IOException {
    final FileEncryptionInfo feInfo = dfsis.getFileEncryptionInfo();
    if (feInfo != null) {
      // File is encrypted, wrap the stream in a crypto stream.
      // Currently only one version, so no special logic based on the version #
      getCryptoProtocolVersion(feInfo);
      final CryptoCodec codec = getCryptoCodec(conf, feInfo);
      final KeyVersion decrypted = decryptEncryptedDataEncryptionKey(feInfo);
      final CryptoInputStream cryptoIn =
              new CryptoInputStream(dfsis, codec, decrypted.getMaterial(),
                      feInfo.getIV());
      return new HdfsDataInputStream(cryptoIn);
    } else {
      // No FileEncryptionInfo so no encryption.
      return new HdfsDataInputStream(dfsis);
    }
  }

  /**
   * Wraps the stream in a CryptoOutputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataOutputStream createWrappedOutputStream(DFSOutputStream dfsos,
                                                        FileSystem.Statistics statistics) throws IOException {
    return createWrappedOutputStream(dfsos, statistics, 0);
  }

  /**
   * Wraps the stream in a CryptoOutputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataOutputStream createWrappedOutputStream(DFSOutputStream dfsos,
                                                        FileSystem.Statistics statistics, long startPos) throws IOException {
    final FileEncryptionInfo feInfo = dfsos.getFileEncryptionInfo();
    if (feInfo != null) {
      // File is encrypted, wrap the stream in a crypto stream.
      // Currently only one version, so no special logic based on the version #
      getCryptoProtocolVersion(feInfo);
      final CryptoCodec codec = getCryptoCodec(conf, feInfo);
      KeyVersion decrypted = decryptEncryptedDataEncryptionKey(feInfo);
      final CryptoOutputStream cryptoOut =
              new CryptoOutputStream(dfsos, codec,
                      decrypted.getMaterial(), feInfo.getIV(), startPos);
      return new HdfsDataOutputStream(cryptoOut, statistics, startPos);
    } else {
      // No FileEncryptionInfo present so no encryption.
      return new HdfsDataOutputStream(dfsos, statistics, startPos);
    }
  }

  public DFSInputStream open(String src)
          throws IOException, UnresolvedLinkException {
    return open(src, dfsClientConf.getIoBufferSize(), true, null);
  }

  /**
   * Create an input stream that obtains a nodelist from the
   * namenode, and then reads from all the right places.  Creates
   * inner subclass of InputStream that does the right out-of-band
   * work.
   * @deprecated Use {@link #open(String, int, boolean)} instead.
   */
  @Deprecated
  public DFSInputStream open(String src, int buffersize, boolean verifyChecksum,
                             FileSystem.Statistics stats)
          throws IOException, UnresolvedLinkException {
    return open(src, buffersize, verifyChecksum);
  }


  /**
   * Create an input stream that obtains a nodelist from the
   * namenode, and then reads from all the right places.  Creates
   * inner subclass of InputStream that does the right out-of-band
   * work.
   */
  public DFSInputStream open(String src, int buffersize, boolean verifyChecksum)
          throws IOException, UnresolvedLinkException {
    checkOpen();
    //    Get block info from namenode
    try (TraceScope ignored = newPathTraceScope("newDFSInputStream", src)) {
      return new DFSInputStream(this, src, verifyChecksum, dfsClientConf.getForceClientToWriteSFToDisk());
    }
  }

  // Added for debugging serverless NN.
  public void printOperationsPerformed() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.printOperationsPerformed();
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public void addOperationPerformed(OperationPerformed operationPerformed) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.addOperationPerformed(operationPerformed);
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public void addOperationPerformeds(Collection<OperationPerformed> operationPerformeds) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.addOperationPerformeds(operationPerformeds);
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public void addOperationPerformeds(OperationPerformed[] operationPerformeds) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.addOperationPerformeds(operationPerformeds);
    } else {
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Return the operations performed by this client.
   */
  public List<OperationPerformed> getOperationsPerformed() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.getOperationsPerformed();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  // Added for debugging serverless NN.
  public int printDebugInformation() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.printDebugInformation();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Print the average latency.
   *
   * If choice <= 0, prints both TCP and HTTP.
   * If choice == 1, prints just TCP.
   * If choice > 1, prints just HTTP.
   * @param choice If choice <= 0, prints both TCP and HTTP. If choice == 1, prints just TCP. If
   *               choice > 1, prints just HTTP.
   */
  public void printLatencyStatistics(int choice) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.printLatencyStatistics(choice);
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Print the max, min, average, and standard deviation of latency.
   *
   * If choice <= 0, prints both TCP and HTTP.
   * If choice == 1, prints just TCP.
   * If choice > 1, prints just HTTP.
   * @param choice If choice <= 0, prints both TCP and HTTP. If choice == 1, prints just TCP. If
   *               choice > 1, prints just HTTP.
   */
  public void printLatencyStatisticsDetailed(int choice) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.printLatencyStatisticsDetailed(choice);
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Dynamically change the threshold at which client stops targeting specific deployments and instead tries
   * to reuse existing TCP connections.
   * @param threshold Updated threshold.
   */
  public void setLatencyThreshold(double threshold) {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.setLatencyThreshold(threshold);
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Get the threshold at which client stops targeting specific deployments and instead tries
   * to reuse existing TCP connections.
   */
  public double getLatencyThreshold() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.getLatencyThreshold();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Clear TCP latency values.
   */
  public void clearLatencyValuesTcp() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.clearLatencyValuesTcp();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Clear HTTP latency values.
   */
  public void clearLatencyValuesHttp() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.clearLatencyValuesHttp();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Clear both TCP and HTTP latency values.
   */
  public void clearLatencyValues() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      client.clearLatencyValues();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public DescriptiveStatistics getLatencyStatistics() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.getLatencyStatistics();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public DescriptiveStatistics getLatencyHttpStatistics() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.getLatencyHttpStatistics();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  public DescriptiveStatistics getLatencyTcpStatistics() {
    if (namenode instanceof ServerlessNameNodeClient) {
      ServerlessNameNodeClient client = (ServerlessNameNodeClient)namenode;
      return client.getLatencyTcpStatistics();
    } else {
      // The type of the `namenode` variable for Serverless HopsFS should be 'ServerlessNameNodeClient'.
      // If it isn't, then none of the Serverless-specific APIs will work.
      throw new IllegalStateException("The internal NameNode client is not of the correct type. That is, it does not implement any Serverless APIs.");
    }
  }

  /**
   * Get the namenode associated with this DFSClient object
   * @return the namenode associated with this DFSClient object
   */
  public ClientProtocol getNamenode() {
    return namenode;
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * default <code>replication</code> and <code>blockSize<code> and null <code>
   * progress</code>.
   */
  public OutputStream create(String src, boolean overwrite)
          throws IOException {
    return create(src, overwrite, dfsClientConf.getDefaultReplication(),
            dfsClientConf.getDefaultBlockSize(), null);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * default <code>replication</code> and <code>blockSize<code>.
   */
  public OutputStream create(String src,
                             boolean overwrite,
                             Progressable progress) throws IOException {
    return create(src, overwrite, dfsClientConf.getDefaultReplication(),
            dfsClientConf.getDefaultBlockSize(), progress);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * null <code>progress</code>.
   */
  public OutputStream create(String src,
                             boolean overwrite,
                             short replication,
                             long blockSize) throws IOException {
    return create(src, overwrite, replication, blockSize, null);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable, int)}
   * with default bufferSize.
   */
  public OutputStream create(String src, boolean overwrite, short replication,
                             long blockSize, Progressable progress) throws IOException {
    return create(src, overwrite, replication, blockSize, progress,
            dfsClientConf.getIoBufferSize());
  }

  /**
   * Call create(String, FsPermission, EnumSet, short, long,
   * Progressable, int, ChecksumOpt) with default <code>permission</code>
   * {@link FsPermission#getFileDefault()}.
   *
   * @param src File name
   * @param overwrite overwrite an existing file if true
   * @param replication replication factor for the file
   * @param blockSize maximum block size
   * @param progress interface for reporting client progress
   * @param buffersize underlying buffersize
   *
   * @return output stream
   */
  public OutputStream create(String src,
                             boolean overwrite,
                             short replication,
                             long blockSize,
                             Progressable progress,
                             int buffersize)
          throws IOException {
    return create(src, FsPermission.getFileDefault(),
            overwrite ? EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE)
                    : EnumSet.of(CreateFlag.CREATE), replication, blockSize, progress,
            buffersize, null, null);
  }

  /**
   * Call {@link #create(String, FsPermission, EnumSet, boolean, short,
   * long, Progressable, int, ChecksumOpt)} with <code>createParent</code>
   *  set to true.
   */
  public DFSOutputStream create(String src,
                                FsPermission permission,
                                EnumSet<CreateFlag> flag,
                                short replication,
                                long blockSize,
                                Progressable progress,
                                int buffersize,
                                ChecksumOpt checksumOpt,
                                EncodingPolicy policy)
          throws IOException {
    return create(src, permission, flag, true,
            replication, blockSize, progress, buffersize, checksumOpt, null, policy);
  }

  /**
   * Create a new dfs file with the specified block replication
   * with write-progress reporting and return an output stream for writing
   * into the file.
   *
   * @param src File name
   * @param permission The permission of the directory being created.
   *          If null, use default permission {@link FsPermission#getFileDefault()}
   * @param flag indicates create a new file or create/overwrite an
   *          existing file or append to an existing file
   * @param createParent create missing parent directory if true
   * @param replication block replication
   * @param blockSize maximum block size
   * @param progress interface for reporting client progress
   * @param buffersize underlying buffer size
   * @param checksumOpt checksum options
   *
   * @return output stream
   *
   * @see ClientProtocol#create for detailed description of exceptions thrown
   */
  public DFSOutputStream create(String src,
                                FsPermission permission,
                                EnumSet<CreateFlag> flag,
                                boolean createParent,
                                short replication,
                                long blockSize,
                                Progressable progress,
                                int buffersize,
                                ChecksumOpt checksumOpt) throws IOException {
    return create(src, permission, flag, createParent, replication, blockSize,
            progress, buffersize, checksumOpt, null, null);
  }

  private FsPermission applyUMask(FsPermission permission) {
    if (permission == null) {
      permission = FsPermission.getFileDefault();
    }
    return permission.applyUMask(dfsClientConf.getUMask());
  }

  /**
   * Same as {@link #create(String, FsPermission, EnumSet, boolean, short, long,
   * Progressable, int, ChecksumOpt)} with the addition of favoredNodes that is
   * a hint to where the namenode should place the file blocks.
   * The favored nodes hint is not persisted in HDFS. Hence it may be honored
   * at the creation time only. HDFS could move the blocks during balancing or
   * replication, to move the blocks from favored nodes. A value of null means
   * no favored nodes for this create
   */
  public DFSOutputStream create(String src,
                                FsPermission permission,
                                EnumSet<CreateFlag> flag,
                                boolean createParent,
                                short replication,
                                long blockSize,
                                Progressable progress,
                                int buffersize,
                                ChecksumOpt checksumOpt,
                                InetSocketAddress[] favoredNodes,
                                EncodingPolicy policy) throws IOException {
    checkOpen();
    final FsPermission masked = applyUMask(permission);
    if(LOG.isDebugEnabled()) {
      LOG.debug(src + ": masked=" + masked);
    }
    final DFSOutputStream result = DFSOutputStream.newStreamForCreate(this,
            src, masked, flag, createParent, replication, blockSize, progress,
            buffersize, dfsClientConf.createChecksum(checksumOpt),
            getFavoredNodesStr(favoredNodes),
            policy, dfsClientConf.getDBFileMaxSize(), dfsClientConf.getForceClientToWriteSFToDisk());
    beginFileLease(result.getFileId(), result);
    return result;
  }

  private String[] getFavoredNodesStr(InetSocketAddress[] favoredNodes) {
    String[] favoredNodeStrs = null;
    if (favoredNodes != null) {
      favoredNodeStrs = new String[favoredNodes.length];
      for (int i = 0; i < favoredNodes.length; i++) {
        favoredNodeStrs[i] =
                favoredNodes[i].getHostName() + ":"
                        + favoredNodes[i].getPort();
      }
    }
    return favoredNodeStrs;
  }

  /**
   * Append to an existing file if {@link CreateFlag#APPEND} is present
   */
  private DFSOutputStream primitiveAppend(String src, EnumSet<CreateFlag> flag,
                                          int buffersize, Progressable progress) throws IOException {
    if (flag.contains(CreateFlag.APPEND)) {
      HdfsFileStatus stat = getFileInfo(src);
      if (stat == null) { // No file to append to
        // New file needs to be created if create option is present
        if (!flag.contains(CreateFlag.CREATE)) {
          throw new FileNotFoundException("failed to append to non-existent file "
                  + src + " on client " + clientName);
        }
        return null;
      }
      return callAppend(src, buffersize, flag, progress, null);
    }
    return null;
  }

  /**
   * Same as create(String, FsPermission, EnumSet, short, long,
   *  Progressable, int, ChecksumOpt) except that the permission
   *  is absolute (ie has already been masked with umask.
   */
  public DFSOutputStream primitiveCreate(String src,
                                         FsPermission absPermission,
                                         EnumSet<CreateFlag> flag,
                                         boolean createParent,
                                         short replication,
                                         long blockSize,
                                         Progressable progress,
                                         int buffersize,
                                         ChecksumOpt checksumOpt)
          throws IOException, UnresolvedLinkException {
    checkOpen();
    CreateFlag.validate(flag);
    DFSOutputStream result = primitiveAppend(src, flag, buffersize, progress);
    if (result == null) {
      DataChecksum checksum = dfsClientConf.createChecksum(checksumOpt);
      result = DFSOutputStream.newStreamForCreate(this, src, absPermission,
              flag, createParent, replication, blockSize, progress, buffersize,
              checksum, null, null, dfsClientConf.getDBFileMaxSize(), dfsClientConf.getForceClientToWriteSFToDisk());
    }
    beginFileLease(result.getFileId(), result);
    return result;
  }

  /**
   * Creates a symbolic link.
   *
   * @see ClientProtocol#createSymlink(String, String,FsPermission, boolean)
   */
  public void createSymlink(String target, String link, boolean createParent)
          throws IOException {
    try (TraceScope ignored = newPathTraceScope("createSymlink", target)) {
      final FsPermission dirPerm = applyUMask(null);
      namenode.createSymlink(target, link, dirPerm, createParent);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileAlreadyExistsException.class,
              FileNotFoundException.class,
              ParentNotDirectoryException.class,
              NSQuotaExceededException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Resolve the *first* symlink, if any, in the path.
   *
   * @see ClientProtocol#getLinkTarget(String)
   */
  public String getLinkTarget(String path) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getLinkTarget", path)) {
      return namenode.getLinkTarget(path);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class);
    }
  }

  /** Method to get stream returned by append call */
  private DFSOutputStream callAppend(String src, int buffersize,
                                     EnumSet<CreateFlag> flag, Progressable progress, String[] favoredNodes)
          throws IOException {
    CreateFlag.validateForAppend(flag);
    try {
      LastBlockWithStatus blkWithStatus = namenode.append(src, clientName,
              new EnumSetWritable<>(flag, CreateFlag.class));
      return DFSOutputStream.newStreamForAppend(this, src, flag, buffersize,
              progress, blkWithStatus.getLastBlock(),
              blkWithStatus.getFileStatus(), dfsClientConf.createChecksum(null),
              favoredNodes,
              dfsClientConf.getDBFileMaxSize(), dfsClientConf.getForceClientToWriteSFToDisk());
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnsupportedOperationException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Append to an existing HDFS file.
   *
   * @param src file name
   * @param buffersize buffer size
   * @param flag indicates whether to append data to a new block instead of
   *             the last block
   * @param progress for reporting write-progress; null is acceptable.
   * @param statistics file system statistics; null is acceptable.
   * @return an output stream for writing into the file
   *
   * @see ClientProtocol#append(String, String, EnumSetWritable)
   */
  public HdfsDataOutputStream append(final String src, final int buffersize,
                                     EnumSet<CreateFlag> flag, final Progressable progress,
                                     final FileSystem.Statistics statistics) throws IOException {
    final DFSOutputStream out = append(src, buffersize, flag, null, progress);
    return createWrappedOutputStream(out, statistics, out.getInitialLen());
  }

  /**
   * Append to an existing HDFS file.
   *
   * @param src file name
   * @param buffersize buffer size
   * @param flag indicates whether to append data to a new block instead of the
   *          last block
   * @param progress for reporting write-progress; null is acceptable.
   * @param statistics file system statistics; null is acceptable.
   * @param favoredNodes FavoredNodes for new blocks
   * @return an output stream for writing into the file
   * @see ClientProtocol#append(String, String, EnumSetWritable)
   */
  public HdfsDataOutputStream append(final String src, final int buffersize,
                                     EnumSet<CreateFlag> flag, final Progressable progress,
                                     final FileSystem.Statistics statistics,
                                     final InetSocketAddress[] favoredNodes) throws IOException {
    final DFSOutputStream out = append(src, buffersize, flag,
            getFavoredNodesStr(favoredNodes), progress);
    return createWrappedOutputStream(out, statistics, out.getInitialLen());
  }

  private DFSOutputStream append(String src, int buffersize,
                                 EnumSet<CreateFlag> flag, String[] favoredNodes, Progressable progress)
          throws IOException {
    checkOpen();
    final DFSOutputStream result = callAppend(src, buffersize, flag, progress,
            favoredNodes);
    beginFileLease(result.getFileId(), result);
    return result;
  }

  /**
   * Set replication for an existing file.
   * @param src file name
   * @param replication
   *
   * @see ClientProtocol#setReplication(String, short)
   */
  public boolean setReplication(String src, short replication)
          throws IOException {
    try (TraceScope ignored = newPathTraceScope("setReplication", src)) {
      return namenode.setReplication(src, replication);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Rename file or directory.
   * @see ClientProtocol#rename(String, String)
   * @deprecated Use {@link #rename(String, String, Options.Rename...)} instead.
   */
  @Deprecated
  public boolean rename(String src, String dst) throws IOException {
    checkOpen();
    try (TraceScope ignored = newSrcDstTraceScope("rename", src, dst)) {
      return namenode.rename(src, dst);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              NSQuotaExceededException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Move blocks from src to trg and delete src
   * See {@link ClientProtocol#concat}.
   */
  public void concat(String trg, String [] srcs) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("concat")) {
      namenode.concat(trg, srcs);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              UnresolvedPathException.class);
    }
  }
  /**
   * Rename file or directory.
   * @see ClientProtocol#rename2(String, String, Options.Rename...)
   */
  public void rename(String src, String dst, Options.Rename... options)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newSrcDstTraceScope("rename2", src, dst)) {
      namenode.rename2(src, dst, options);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              FileAlreadyExistsException.class,
              FileNotFoundException.class,
              ParentNotDirectoryException.class,
              SafeModeException.class,
              NSQuotaExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Truncate a file to an indicated size
   * See {@link ClientProtocol#truncate}.
   */
  public boolean truncate(String src, long newLength) throws IOException {
    checkOpen();
    if (newLength < 0) {
      throw new HadoopIllegalArgumentException(
              "Cannot truncate to a negative file size: " + newLength + ".");
    }
    try {
      return namenode.truncate(src, newLength, clientName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Delete file or directory.
   * See {@link ClientProtocol#delete(String, boolean)}.
   */
  @Deprecated
  public boolean delete(String src) throws IOException {
    checkOpen();
    return delete(src, true);
  }

  /**
   * delete file or directory.
   * delete contents of the directory if non empty and recursive
   * set to true
   *
   * @see ClientProtocol#delete(String, boolean)
   */
  public boolean delete(String src, boolean recursive) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("delete", src)) {
      /*if (getServerDefaults().getQuotaEnabled()) {
        return leaderNN.delete(src, recursive);
      } else {
        return namenode.delete(src, recursive);
      }*/
      // Serverless version does not have a leader name node.
      return namenode.delete(src, recursive);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public JsonObject latencyBenchmark(String connectionUrl, String dataSource, String query, int id)
          throws IOException, SQLException {
    return namenode.latencyBenchmark(connectionUrl, dataSource, query, id);
  }

  /** Implemented using getFileInfo(src)
   */
  public boolean exists(String src) throws IOException {
    checkOpen();
    return getFileInfo(src) != null;
  }

  /**
   * Get a partial listing of the indicated directory
   * No block locations need to be fetched
   */
  public DirectoryListing listPaths(String src,  byte[] startAfter)
          throws IOException {
    return listPaths(src, startAfter, false);
  }

  /**
   * Get a partial listing of the indicated directory
   *
   * Recommend to use HdfsFileStatus.EMPTY_NAME as startAfter
   * if the application wants to fetch a listing starting from
   * the first entry in the directory
   *
   * @see ClientProtocol#getListing(String, byte[], boolean)
   */
  public DirectoryListing listPaths(String src,  byte[] startAfter,
                                    boolean needLocation)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("listPaths", src)) {
      LOG.debug("Listing paths for target directory " + src);
      return namenode.getListing(src, startAfter, needLocation);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Get the file info for a specific file or directory.
   * @param src The string representation of the path to the file
   * @return object containing information regarding the file
   *         or null if file not found
   *
   * @see ClientProtocol#getFileInfo(String) for description of exceptions
   */
  public HdfsFileStatus getFileInfo(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getFileInfo", src)) {
      LOG.debug("Getting file info for file/directory " + src);
      return namenode.getFileInfo(src);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Close status of a file
   * @return true if file is already closed
   */
  public boolean isFileClosed(String src) throws IOException{
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("isFileClosed", src)) {
      return namenode.isFileClosed(src);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Get the file info for a specific file or directory. If src
   * refers to a symlink then the FileStatus of the link is returned.
   * @param src path to a file or directory.
   *
   * For description of exceptions thrown
   * @see ClientProtocol#getFileLinkInfo(String)
   */
  public HdfsFileStatus getFileLinkInfo(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getFileLinkInfo", src)) {
      return namenode.getFileLinkInfo(src);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              UnresolvedPathException.class);
    }
  }

  @InterfaceAudience.Private
  public void clearDataEncryptionKey() {
    LOG.debug("Clearing encryption key");
    synchronized (this) {
      encryptionKey = null;
    }
  }

  /**
   * @return true if data sent between this client and DNs should be encrypted,
   *         false otherwise.
   * @throws IOException in the event of error communicating with the NN
   */
  boolean shouldEncryptData() throws IOException {
    FsServerDefaults d = getServerDefaults();
    return d == null ? false : d.getEncryptDataTransfer();
  }

  @Override
  public DataEncryptionKey newDataEncryptionKey() throws IOException {
    if (shouldEncryptData()) {
      synchronized (this) {
        if (encryptionKey == null ||
                encryptionKey.expiryDate < Time.now()) {
          LOG.debug("Getting new encryption token from NN");
          encryptionKey = namenode.getDataEncryptionKey();
        }
        return encryptionKey;
      }
    } else {
      return null;
    }
  }

  /**
   * Get the checksum of the whole file of a range of the file. Note that the
   * range always starts from the beginning of the file.
   * @param src The file path
   * @param length the length of the range, i.e., the range is [0, length]
   * @return The checksum
   * @see DistributedFileSystem#getFileChecksum(Path)
   */
  public MD5MD5CRC32FileChecksum getFileChecksum(String src, long length)
          throws IOException {
    checkOpen();
    Preconditions.checkArgument(length >= 0);
    //get all block locations
    LocatedBlocks blockLocations = callGetBlockLocations(namenode, src, 0, length);
    if (null == blockLocations) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    List<LocatedBlock> locatedblocks = blockLocations.getLocatedBlocks();
    final DataOutputBuffer md5out = new DataOutputBuffer();
    int bytesPerCRC = -1;
    DataChecksum.Type crcType = DataChecksum.Type.DEFAULT;
    long crcPerBlock = 0;
    boolean refetchBlocks = false;
    int lastRetriedIndex = -1;

    // get block checksum for each block
    long remaining = length;
    for(int i = 0; i < locatedblocks.size() && remaining > 0; i++) {
      if (refetchBlocks) {  // refetch to get fresh tokens
        blockLocations = callGetBlockLocations(namenode, src, 0, length);
        if (null == blockLocations) {
          throw new FileNotFoundException("File does not exist: " + src);
        }
        locatedblocks = blockLocations.getLocatedBlocks();
        refetchBlocks = false;
      }
      LocatedBlock lb = locatedblocks.get(i);
      final ExtendedBlock block = lb.getBlock();
      if (remaining < block.getNumBytes()) {
        block.setNumBytes(remaining);
      }
      remaining -= block.getNumBytes();
      final DatanodeInfo[] datanodes = lb.getLocations();
      if (remaining < block.getNumBytes()) {
        block.setNumBytes(remaining);
      }
      remaining -= block.getNumBytes();

      //try each datanode location of the block
      final int timeout = 3000*datanodes.length + dfsClientConf.getSocketTimeout();
      boolean done = false;
      for(int j = 0; !done && j < datanodes.length; j++) {
        DataOutputStream out = null;
        DataInputStream in = null;

        try {
          //connect to a datanode
          IOStreamPair pair = connectToDN(datanodes[j], timeout, lb);
          out = new DataOutputStream(new BufferedOutputStream(pair.out,
                  HdfsConstants.SMALL_BUFFER_SIZE));
          in = new DataInputStream(pair.in);

          if (LOG.isDebugEnabled()) {
            LOG.debug("write to " + datanodes[j] + ": "
                    + Op.BLOCK_CHECKSUM + ", block=" + block);
          }
          // get block MD5
          new Sender(out).blockChecksum(block, lb.getBlockToken());

          final BlockOpResponseProto reply =
                  BlockOpResponseProto.parseFrom(PBHelper.vintPrefixed(in));

          String logInfo = "for block " + block + " from datanode " + datanodes[j];
          DataTransferProtoUtil.checkBlockOpStatus(reply, logInfo);

          OpBlockChecksumResponseProto checksumData =
                  reply.getChecksumResponse();

          //read byte-per-checksum
          final int bpc = checksumData.getBytesPerCrc();
          if (i == 0) { //first block
            bytesPerCRC = bpc;
          }
          else if (bpc != bytesPerCRC) {
            throw new IOException("Byte-per-checksum not matched: bpc=" + bpc
                    + " but bytesPerCRC=" + bytesPerCRC);
          }

          //read crc-per-block
          final long cpb = checksumData.getCrcPerBlock();
          if (locatedblocks.size() > 1 && i == 0) {
            crcPerBlock = cpb;
          }

          //read md5
          final MD5Hash md5 = new MD5Hash(
                  checksumData.getMd5().toByteArray());
          md5.write(md5out);

          // read crc-type
          final DataChecksum.Type ct;
          if (checksumData.hasCrcType()) {
            ct = PBHelper.convert(checksumData
                    .getCrcType());
          } else {
            LOG.debug("Retrieving checksum from an earlier-version DataNode: " +
                    "inferring checksum by reading first byte");
            ct = inferChecksumTypeByReading(lb, datanodes[j]);
          }

          if (i == 0) { // first block
            crcType = ct;
          } else if (crcType != DataChecksum.Type.MIXED
                  && crcType != ct) {
            // if crc types are mixed in a file
            crcType = DataChecksum.Type.MIXED;
          }

          done = true;

          if (LOG.isDebugEnabled()) {
            if (i == 0) {
              LOG.debug("set bytesPerCRC=" + bytesPerCRC
                      + ", crcPerBlock=" + crcPerBlock);
            }
            LOG.debug("got reply from " + datanodes[j] + ": md5=" + md5);
          }
        } catch (InvalidBlockTokenException ibte) {
          if (i > lastRetriedIndex) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Got access token error in response to OP_BLOCK_CHECKSUM "
                      + "for file " + src + " for block " + block
                      + " from datanode " + datanodes[j]
                      + ". Will retry the block once.");
            }
            lastRetriedIndex = i;
            done = true; // actually it's not done; but we'll retry
            i--; // repeat at i-th block
            refetchBlocks = true;
            break;
          }
        } catch (IOException ie) {
          LOG.warn("src=" + src + ", datanodes["+j+"]=" + datanodes[j], ie);
        } finally {
          IOUtils.closeStream(in);
          IOUtils.closeStream(out);
        }
      }

      if (!done) {
        throw new IOException("Fail to get block MD5 for " + block);
      }
    }

    //compute file MD5
    final MD5Hash fileMD5 = MD5Hash.digest(md5out.getData());
    switch (crcType) {
      case CRC32:
        return new MD5MD5CRC32GzipFileChecksum(bytesPerCRC,
                crcPerBlock, fileMD5);
      case CRC32C:
        return new MD5MD5CRC32CastagnoliFileChecksum(bytesPerCRC,
                crcPerBlock, fileMD5);
      default:
        // If there is no block allocated for the file,
        // return one with the magic entry that matches what previous
        // hdfs versions return.
        if (locatedblocks.size() == 0) {
          return new MD5MD5CRC32GzipFileChecksum(0, 0, fileMD5);
        }

        // we should never get here since the validity was checked
        // when getCrcType() was called above.
        return null;
    }
  }

  /**
   * Connect to the given datanode's datantrasfer port, and return
   * the resulting IOStreamPair. This includes encryption wrapping, etc.
   */
  private IOStreamPair connectToDN(DatanodeInfo dn, int timeout,
                                   LocatedBlock lb) throws IOException {
    boolean success = false;
    Socket sock = null;
    try {
      sock = socketFactory.createSocket();
      String dnAddr = dn.getXferAddr(getConf().isConnectToDnViaHostname());
      if (LOG.isDebugEnabled()) {
        LOG.debug("Connecting to datanode " + dnAddr);
      }
      long connectStart = System.currentTimeMillis();
      NetUtils.connect(sock, NetUtils.createSocketAddr(dnAddr), timeout);
      sock.setSoTimeout(timeout);

      OutputStream unbufOut = NetUtils.getOutputStream(sock);
      InputStream unbufIn = NetUtils.getInputStream(sock);
      IOStreamPair ret = saslClient.newSocketSend(sock, unbufOut, unbufIn, this,
              lb.getBlockToken(), dn);
      success = true;
      long connectEnd = System.currentTimeMillis();
      long connectDuration = connectEnd - connectStart;
      LOG.debug("Connected to DataNode " + dnAddr + " in " + (connectDuration / 1000000) + " milliseconds.");
      return ret;
    } finally {
      if (!success) {
        IOUtils.closeSocket(sock);
      }
    }
  }

  /**
   * Infer the checksum type for a replica by sending an OP_READ_BLOCK
   * for the first byte of that replica. This is used for compatibility
   * with older HDFS versions which did not include the checksum type in
   * OpBlockChecksumResponseProto.
   *
   * @param lb the located block
   * @param dn the connected datanode
   * @return the inferred checksum type
   * @throws IOException if an error occurs
   */
  private Type inferChecksumTypeByReading(LocatedBlock lb, DatanodeInfo dn)
          throws IOException {
    IOStreamPair pair = connectToDN(dn, dfsClientConf.getSocketTimeout(), lb);

    try {
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(pair.out,
              HdfsConstants.SMALL_BUFFER_SIZE));
      DataInputStream in = new DataInputStream(pair.in);

      new Sender(out).readBlock(lb.getBlock(), lb.getBlockToken(), clientName,
              0, 1, true, CachingStrategy.newDefaultStrategy());
      final BlockOpResponseProto reply =
              BlockOpResponseProto.parseFrom(PBHelper.vintPrefixed(in));
      String logInfo = "trying to read " + lb.getBlock() + " from datanode " + dn;
      DataTransferProtoUtil.checkBlockOpStatus(reply, logInfo);

      return PBHelper.convert(reply.getReadOpChecksumInfo().getChecksum().getType());
    } finally {
      IOUtils.cleanup(null, pair.in, pair.out);
    }
  }

  /**
   * Set permissions to a file or directory.
   * @param src path name.
   * @param permission
   *
   * @see ClientProtocol#setPermission(String, FsPermission)
   */
  public void setPermission(String src, FsPermission permission)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setPermission", src)) {
      namenode.setPermission(src, permission);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Set file or directory owner.
   * @param src path name.
   * @param username user id.
   * @param groupname user group.
   *
   * @see ClientProtocol#setOwner(String, String, String)
   */
  public void setOwner(String src, String username, String groupname)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setOwner", src)) {
      namenode.setOwner(src, username, groupname);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  private long[] callGetStats() throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("getStats")) {
      return namenode.getStats();
    }
  }
  /**
   * @see ClientProtocol#getStats()
   */
  public FsStatus getDiskStatus() throws IOException {
    long[] rawNums = callGetStats();
    return new FsStatus(rawNums[0], rawNums[1], rawNums[2]);
  }

  /**
   * Returns count of blocks with no good replicas left. Normally should be
   * zero.
   * @throws IOException
   */
  public long getMissingBlocksCount() throws IOException {
    return callGetStats()[ClientProtocol.GET_STATS_MISSING_BLOCKS_IDX];
  }

  /**
   * Returns count of blocks with replication factor 1 and have
   * lost the only replica.
   * @throws IOException
   */
  public long getMissingReplOneBlocksCount() throws IOException {
    return callGetStats()[ClientProtocol.
            GET_STATS_MISSING_REPL_ONE_BLOCKS_IDX];
  }

  /**
   * Returns count of blocks with one of more replica missing.
   * @throws IOException
   */
  public long getUnderReplicatedBlocksCount() throws IOException {
    return callGetStats()[ClientProtocol.GET_STATS_UNDER_REPLICATED_IDX];
  }

  /**
   * Returns count of blocks with at least one replica marked corrupt.
   * @throws IOException
   */
  public long getCorruptBlocksCount() throws IOException {
    return callGetStats()[ClientProtocol.GET_STATS_CORRUPT_BLOCKS_IDX];
  }

  /**
   * @return a list in which each entry describes a corrupt file/block
   * @throws IOException
   */
  public CorruptFileBlocks listCorruptFileBlocks(String path,
                                                 String cookie)
          throws IOException {
    checkOpen();
    try (TraceScope ignored
                 = newPathTraceScope("listCorruptFileBlocks", path)) {
      return namenode.listCorruptFileBlocks(path, cookie);
    }
  }

  public DatanodeInfo[] datanodeReport(DatanodeReportType type)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("datanodeReport")) {
      return namenode.getDatanodeReport(type);
    }
  }

  public DatanodeStorageReport[] getDatanodeStorageReport(
          DatanodeReportType type) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("datanodeStorageReport")) {

      return namenode.getDatanodeStorageReport(type);
    }
  }

  /**
   * Enter, leave or get safe mode.
   *
   * @see ClientProtocol#setSafeMode(HdfsConstants.SafeModeAction,boolean)
   */
  public boolean setSafeMode(SafeModeAction action) throws IOException {
    return setSafeMode(action, false);
  }

  /**
   * Enter, leave or get safe mode.
   *
   * @param action
   *          One of SafeModeAction.GET, SafeModeAction.ENTER and
   *          SafeModeActiob.LEAVE
   * @param isChecked
   *          If true, then check only active namenode's safemode status, else
   *          check first namenode's status.
   * @see ClientProtocol#setSafeMode(HdfsConstants.SafeModeAction, boolean)
   */
  public boolean setSafeMode(SafeModeAction action, boolean isChecked) throws IOException{
    if(leaderNN==null){
      throw new IOException("There is no leader NameNode available!");
    }
    try (TraceScope ignored = tracer.newScope("setSafeMode")) {
      for (ClientProtocol nn : allNNs) {
        if (!proxyEquals(nn, leaderNN)) {
          nn.setSafeMode(action, isChecked);
        }
      }
      return leaderNN.setSafeMode(action, isChecked);
    }
  }

  public long addCacheDirective(
          final CacheDirectiveInfo info, final EnumSet<CacheFlag> flags) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("addCacheDirective")) {
      if (!flags.contains(CacheFlag.FORCE)) {
        return leaderNN.addCacheDirective(info, flags);
      }else {
        return namenode.addCacheDirective(info, flags);
      }
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void modifyCacheDirective(
          final CacheDirectiveInfo info, final EnumSet<CacheFlag> flags) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("modifyCacheDirective")) {
      if (!flags.contains(CacheFlag.FORCE)) {
        leaderNN.modifyCacheDirective(info, flags);
      } else {
        namenode.modifyCacheDirective(info, flags);
      }
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void removeCacheDirective(final long id)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeCacheDirective")) {
      namenode.removeCacheDirective(id);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public RemoteIterator<CacheDirectiveEntry> listCacheDirectives(
          CacheDirectiveInfo filter) throws IOException {
    return new CacheDirectiveIterator(leaderNN, filter, tracer);
  }

  public void addCachePool(final CachePoolInfo info) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("addCachePool")) {
      leaderNN.addCachePool(info);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void modifyCachePool(final CachePoolInfo info) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("modifyCachePool")) {
      namenode.modifyCachePool(info);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void removeCachePool(final String poolName) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeCachePool")) {
      namenode.removeCachePool(poolName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public RemoteIterator<CachePoolEntry> listCachePools() throws IOException {

    return new CachePoolIterator(leaderNN, tracer);

  }

  @VisibleForTesting
  ExtendedBlock getPreviousBlock(long fileId) {
    return filesBeingWritten.get(fileId).getBlock();
  }

  /**
   * Refresh the hosts and exclude files.  (Rereads them.)
   * See {@link ClientProtocol#refreshNodes()}
   * for more details.
   *
   * @see ClientProtocol#refreshNodes()
   */
  public void refreshNodes() throws IOException {
    try (TraceScope ignored = tracer.newScope("refreshNodes")) {
      namenode.refreshNodes();
    }
  }

  /**
   * Requests the namenode to tell all datanodes to use a new, non-persistent
   * bandwidth value for dfs.balance.bandwidthPerSec.
   * See {@link ClientProtocol#setBalancerBandwidth(long)}
   * for more details.
   *
   * @see ClientProtocol#setBalancerBandwidth(long)
   */
  public void setBalancerBandwidth(long bandwidth) throws IOException {
    try (TraceScope ignored = tracer.newScope("setBalancerBandwidth")) {
      namenode.setBalancerBandwidth(bandwidth);
    }
  }

  RollingUpgradeInfo rollingUpgrade(RollingUpgradeAction action) throws IOException {
    try (TraceScope ignored = tracer.newScope("rollingUpgrade")) {
      return namenode.rollingUpgrade(action);
    }
  }

  /**
   */
  @Deprecated
  public boolean mkdirs(String src) throws IOException {
    return mkdirs(src, null, true);
  }

  /**
   * Create a directory (or hierarchy of directories) with the given
   * name and permission.
   *
   * @param src The path of the directory being created
   * @param permission The permission of the directory being created.
   * If permission == null, use {@link FsPermission#getDefault()}.
   * @param createParent create missing parent directory if true
   *
   * @return True if the operation success.
   *
   * @see ClientProtocol#mkdirs(String, FsPermission, boolean)
   */
  public boolean mkdirs(String src, FsPermission permission,
                        boolean createParent) throws IOException {
    final FsPermission masked = applyUMask(permission);
    return primitiveMkdir(src, masked, createParent);
  }

  /**
   * Same {{@link #mkdirs(String, FsPermission, boolean)} except
   * that the permissions has already been masked against umask.
   */
  public boolean primitiveMkdir(String src, FsPermission absPermission)
          throws IOException {
    return primitiveMkdir(src, absPermission, true);
  }

  /**
   * Same {{@link #mkdirs(String, FsPermission, boolean)} except
   * that the permissions has already been masked against umask.
   */
  public boolean primitiveMkdir(String src, FsPermission absPermission,
                                boolean createParent)
          throws IOException {

    checkOpen();
    if (absPermission == null) {
      absPermission = applyUMask(null);
    }

    if(LOG.isDebugEnabled()) {
      LOG.debug(src + ": masked=" + absPermission);
    }
    try (TraceScope ignored = tracer.newScope("mkdir")) {
      return namenode.mkdirs(src, absPermission, createParent);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              InvalidPathException.class,
              FileAlreadyExistsException.class,
              FileNotFoundException.class,
              ParentNotDirectoryException.class,
              SafeModeException.class,
              NSQuotaExceededException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Get {@link ContentSummary} rooted at the specified directory.
   * @param src The string representation of the path
   *
   * @see ClientProtocol#getContentSummary(String)
   */
  ContentSummary getContentSummary(String src) throws IOException {
    try (TraceScope ignored = newPathTraceScope("getContentSummary", src)) {
      return namenode.getContentSummary(src);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Sets or resets quotas for a directory.
   * @see ClientProtocol#setQuota(String, long, long, StorageType)
   */
  void setQuota(String src, long namespaceQuota, long storagespaceQuota)
          throws IOException {
    // sanity check
    if ((namespaceQuota <= 0 && namespaceQuota != HdfsConstants.QUOTA_DONT_SET &&
            namespaceQuota != HdfsConstants.QUOTA_RESET) ||
            (storagespaceQuota <= 0 && storagespaceQuota != HdfsConstants.QUOTA_DONT_SET &&
                    storagespaceQuota != HdfsConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Invalid values for quota : " +
              namespaceQuota + " and " +
              storagespaceQuota);

    }
    try (TraceScope ignored = newPathTraceScope("setQuota", src)) {
      // Pass null as storage type for traditional namespace/storagespace quota.
      leaderNN.setQuota(src, namespaceQuota, storagespaceQuota, null);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Sets or resets quotas by storage type for a directory.
   * @see ClientProtocol#setQuota(String, long, long, StorageType)
   */
  void setQuotaByStorageType(String src, StorageType type, long quota)
          throws IOException {
    if (quota <= 0 && quota != HdfsConstants.QUOTA_DONT_SET &&
            quota != HdfsConstants.QUOTA_RESET) {
      throw new IllegalArgumentException("Invalid values for quota :" +
              quota);
    }
    if (type == null) {
      throw new IllegalArgumentException("Invalid storage type(null)");
    }
    if (!type.supportTypeQuota()) {
      throw new IllegalArgumentException("Don't support Quota for storage type : "
              + type.toString());
    }
    try (TraceScope ignored = newPathTraceScope("setQuotaByStorageType", src)) {
      leaderNN.setQuota(src, HdfsConstants.QUOTA_DONT_SET, quota, type);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              QuotaByStorageTypeExceededException.class,
              UnresolvedPathException.class);
    }
  }
  /**
   * set the modification and access time of a file
   *
   * @see ClientProtocol#setTimes(String, long, long)
   */
  public void setTimes(String src, long mtime, long atime) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setTimes", src)) {
      namenode.setTimes(src, mtime, atime);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public void createEncryptionZone(String src, String keyName)
          throws IOException {
    checkOpen();
    try {
      namenode.createEncryptionZone(src, keyName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public EncryptionZone getEZForPath(String src)
          throws IOException {
    checkOpen();
    try {
      return namenode.getEZForPath(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              UnresolvedPathException.class);
    }
  }

  public RemoteIterator<EncryptionZone> listEncryptionZones()
          throws IOException {
    checkOpen();
    return new EncryptionZoneIterator(namenode);
  }

  public void setXAttr(String src, String name, byte[] value,
                       EnumSet<XAttrSetFlag> flag) throws IOException {
    checkOpen();
    try {
      namenode.setXAttr(src, XAttrHelper.buildXAttr(name, value), flag);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public byte[] getXAttr(String src, String name) throws IOException {
    checkOpen();
    try {
      final List<XAttr> xAttrs = XAttrHelper.buildXAttrAsList(name);
      final List<XAttr> result = namenode.getXAttrs(src, xAttrs);
      return XAttrHelper.getFirstXAttrValue(result);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public Map<String, byte[]> getXAttrs(String src) throws IOException {
    checkOpen();
    try {
      return XAttrHelper.buildXAttrMap(namenode.getXAttrs(src, null));
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public Map<String, byte[]> getXAttrs(String src, List<String> names)
          throws IOException {
    checkOpen();
    try {
      return XAttrHelper.buildXAttrMap(namenode.getXAttrs(
              src, XAttrHelper.buildXAttrs(names)));
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public List<String> listXAttrs(String src)
          throws IOException {
    checkOpen();
    try {
      final Map<String, byte[]> xattrs =
              XAttrHelper.buildXAttrMap(namenode.listXAttrs(src));
      return Lists.newArrayList(xattrs.keySet());
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public void removeXAttr(String src, String name) throws IOException {
    checkOpen();
    try {
      namenode.removeXAttr(src, XAttrHelper.buildXAttr(name));
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * @deprecated use {@link HdfsDataInputStream} instead.
   */
  @Deprecated
  public static class DFSDataInputStream extends HdfsDataInputStream {

    public DFSDataInputStream(DFSInputStream in) throws IOException {
      super(in);
    }
  }

  void reportChecksumFailure(String file, ExtendedBlock blk, DatanodeInfo dn) {
    DatanodeInfo [] dnArr = { dn };
    LocatedBlock [] lblocks = { new LocatedBlock(blk, dnArr) };
    reportChecksumFailure(file, lblocks);
  }

  // just reports checksum failure and ignores any exception during the report.
  void reportChecksumFailure(String file, LocatedBlock lblocks[]) {
    try {
      reportBadBlocks(lblocks);
    } catch (IOException ie) {
      LOG.info("Found corruption while reading " + file
              + ". Error repairing corrupt blocks. Bad blocks remain.", ie);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[clientName=" + clientName
            + ", ugi=" + ugi + "]";
  }

  public CachingStrategy getDefaultReadCachingStrategy() {
    return defaultReadCachingStrategy;
  }

  public CachingStrategy getDefaultWriteCachingStrategy() {
    return defaultWriteCachingStrategy;
  }

  public EncodingStatus getEncodingStatus(final String filePath)
          throws IOException {
    try {
      return namenode.getEncodingStatus(filePath);
    } catch (RemoteException e) {
      throw e.unwrapRemoteException();
    }
  }

  /**
   * @see org.apache.hadoop.hdfs.protocol.ClientProtocol#encodeFile
   */
  public void encodeFile(final String filePath, final EncodingPolicy policy)
          throws IOException {
    namenode.encodeFile(filePath, policy);
  }

  /**
   * @see org.apache.hadoop.hdfs.protocol.ClientProtocol#revokeEncoding
   */
  public void revokeEncoding(final String filePath, final short replication)
          throws IOException {
    namenode.revokeEncoding(filePath, replication);
  }

  /**
   * @see org.apache.hadoop.hdfs.protocol.ClientProtocol#getRepairedBlockLocations
   */
  public LocatedBlock getRepairedBlockLocations(final String sourcePath,
                                                final String parityPath, final LocatedBlock block, final boolean isParity)
          throws IOException {
    return callGetRepairedBlockLocations(namenode, sourcePath, parityPath,
            block, isParity);
  }

  static LocatedBlock callGetRepairedBlockLocations(ClientProtocol namenode,
                                                    String sourcePath, String parityPath, LocatedBlock block,
                                                    boolean isParity) throws IOException {
    try {
      return namenode.getRepairedBlockLocations(sourcePath, parityPath, block, isParity);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, UnresolvedPathException.class);
    }
  }

  public void changeConf(final List<String> props, final List<String> newVals)
          throws IOException {
    for(ClientProtocol nn : allNNs){
      nn.changeConf(props, newVals);
    }
  }

  public void checkAccess(final String src, final FsAction mode)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("checkAccess", src)) {
      namenode.checkAccess(src, mode);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public void modifyAclEntries(final String src, final List<AclEntry> aclSpec)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("modifyAclEntries", src)) {
      namenode.modifyAclEntries(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public void removeAclEntries(final String src, final List<AclEntry> aclSpec)
          throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeAclEntries")) {
      namenode.removeAclEntries(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public void removeDefaultAcl(final String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeDefaultAcl")) {
      namenode.removeDefaultAcl(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public void removeAcl(final String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeAcl")) {
      namenode.removeAcl(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public void setAcl(final String src, final List<AclEntry> aclSpec) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("setAcl")) {
      namenode.setAcl(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              NSQuotaExceededException.class,
              SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  public AclStatus getAclStatus(final String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getAclStatus", src)) {
      return namenode.getAclStatus(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AclException.class,
              FileNotFoundException.class,
              UnresolvedPathException.class);
    }
  }

  public LocatedBlock getAdditionalDatanode(final String src, long fileId,
                                            final ExtendedBlock blk, final DatanodeInfo[] existings,
                                            final String[] existingStorages, final DatanodeInfo[] excludes,
                                            final int numAdditionalNodes, final String clientName)
          throws IOException {

    try {
      return namenode.getAdditionalDatanode(src, fileId, blk, existings, existingStorages,
              excludes, numAdditionalNodes, clientName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              UnresolvedLinkException.class);
    }
  }

  /**
   * Set storage policy for an existing file/directory
   * @param src file/directory name
   * @param policyName name of the storage policy
   */
  public void setStoragePolicy(final String src, final String policyName)
          throws IOException {
    try (TraceScope ignored = newPathTraceScope("setStoragePolicy", src)) {
      namenode.setStoragePolicy(src, policyName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class,
              SafeModeException.class,
              NSQuotaExceededException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * @return All the existing storage policies
   */
  public BlockStoragePolicy getStoragePolicy(final byte storagePolicyID) throws IOException {
    try (TraceScope ignored = tracer.newScope("getStoragePolicy")) {
      return namenode.getStoragePolicy(storagePolicyID);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, SafeModeException.class,
              DSQuotaExceededException.class, UnresolvedPathException.class);
    }
  }

  /**
   * @return All the existing storage policies
   */
  public BlockStoragePolicy[] getStoragePolicies() throws IOException {
    try (TraceScope ignored = tracer.newScope("getStoragePolicies")) {
      return namenode.getStoragePolicies();
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, SafeModeException.class,
              DSQuotaExceededException.class, UnresolvedPathException.class);
    }
  }

  public void setMetaStatus(final String src, final MetaStatus metaStatus)
          throws IOException {
    try (TraceScope ignored = tracer.newScope("setMetaStatus")) {
      namenode.setMetaStatus(src, metaStatus);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, SafeModeException.class,
              UnresolvedPathException.class);
    }
  }

  /**
   * Return the list of actively-running NameNodes.
   */
  public SortedActiveNodeList getActiveNamenodesForClient() throws IOException {
    return namenode.getActiveNamenodesForClient();
  }

  public int getNameNodesCount() throws IOException {
    return namenode.getActiveNamenodesForClient().size();
  }

  /**
   * Get {@link ContentSummary} rooted at the specified directory.
   *
   * @param src
   *     The string representation of the path
   * @see ClientProtocol#getLastUpdatedContentSummary(String)
   */
  LastUpdatedContentSummary getLastUpdatedContentSummary(final String src) throws
          IOException {
    try {
      return namenode.getLastUpdatedContentSummary(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, UnresolvedPathException.class);
    }
  }

  /**
   * @see ClientProtocol#getMissingBlockLocations(String)
   */
  public LocatedBlocks getMissingLocatedBlocks(final String src)
          throws IOException {
    try {
      return namenode.getMissingBlockLocations(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
              FileNotFoundException.class, UnresolvedPathException.class);
    }
  }

  /**
   * @see ClientProtocol#addBlockChecksum(String, int, long)
   */
  public void addBlockChecksum(final String src, final int blockIndex,
                               final long checksum) throws IOException {
    try {
      namenode.addBlockChecksum(src, blockIndex, checksum);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * @see ClientProtocol#getBlockChecksum(String, int)
   */
  public long getBlockChecksum(final String filePath, final int blockIndex)
          throws IOException {
    try {
      return namenode.getBlockChecksum(filePath, blockIndex);
    } catch (RemoteException e) {
      throw e.unwrapRemoteException();
    }
  }

  public DFSOutputStream sendBlock(String src, LocatedBlock block,
                                   Progressable progress, ChecksumOpt checksumOpt) throws IOException {
    checkOpen();
    HdfsFileStatus stat = getFileInfo(src);
    if (stat == null) { // No file found
      throw new FileNotFoundException(
              "failed to append to non-existent file " + src + " on client " +
                      clientName);
    }
    final DFSOutputStream result = DFSOutputStream
            .newStreamForSingleBlock(this, src,
                    progress, block, dfsClientConf.createChecksum(checksumOpt), stat);
    return result;
  }

  public ClientContext getClientContext() {
    return clientContext;
  }

  @Override // RemotePeerFactory
  public Peer newConnectedPeer(InetSocketAddress addr,
                               Token<BlockTokenIdentifier> blockToken, DatanodeID datanodeId)
          throws IOException {
    Peer peer = null;
    boolean success = false;
    Socket sock = null;
    final int socketTimeout = dfsClientConf.getSocketTimeout();
    try {
      sock = socketFactory.createSocket();
      NetUtils.connect(sock, addr, getRandomLocalInterfaceAddr(), socketTimeout);
      peer = TcpPeerServer.peerFromSocketAndKey(saslClient, sock, this,
              blockToken, datanodeId);
      peer.setReadTimeout(socketTimeout);
      success = true;
      return peer;
    } finally {
      if (!success) {
        IOUtils.cleanup(LOG, peer);
        IOUtils.closeSocket(sock);
      }
    }
  }

  /**
   * Create hedged reads thread pool, HEDGED_READ_THREAD_POOL, if
   * it does not already exist.
   *
   * @param num Number of threads for hedged reads thread pool.
   * If zero, skip hedged reads thread pool creation.
   */
  private synchronized void initThreadsNumForHedgedReads(int num) {
    if (num <= 0 || HEDGED_READ_THREAD_POOL != null) {
      return;
    }
    HEDGED_READ_THREAD_POOL = new ThreadPoolExecutor(1, num, 60,
            TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            new Daemon.DaemonFactory() {
              private final AtomicInteger threadIndex = new AtomicInteger(0);

              @Override
              public Thread newThread(Runnable r) {
                Thread t = super.newThread(r);
                t.setName("hedgedRead-" + threadIndex.getAndIncrement());
                return t;
              }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() {

              @Override
              public void rejectedExecution(Runnable runnable,
                                            ThreadPoolExecutor e) {
                LOG.info("Execution rejected, Executing in current thread");
                HEDGED_READ_METRIC.incHedgedReadOpsInCurThread();
                // will run in the current thread
                super.rejectedExecution(runnable, e);
              }
            });
    HEDGED_READ_THREAD_POOL.allowCoreThreadTimeOut(true);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Using hedged reads; pool threads=" + num);
    }
  }

  ThreadPoolExecutor getHedgedReadsThreadPool() {
    return HEDGED_READ_THREAD_POOL;
  }

  boolean isHedgedReadsEnabled() {
    return (HEDGED_READ_THREAD_POOL != null) && HEDGED_READ_THREAD_POOL.getMaximumPoolSize() > 0;
  }

  DFSHedgedReadMetrics getHedgedReadMetrics() {
    return HEDGED_READ_METRIC;
  }

  public KeyProvider getKeyProvider() {
    return clientContext.getKeyProviderCache().get(conf);
  }

  @VisibleForTesting
  public void setKeyProvider(KeyProvider provider) {
    try {
      clientContext.getKeyProviderCache().setKeyProvider(conf, provider);
    } catch (IOException e) {
      LOG.error("Could not set KeyProvider !!", e);
    }
  }

  public boolean hasLeader(){
    return leaderNN!=null;
  }

  public void addUser(String userName) throws IOException{
    try{
      namenode.addUser(userName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }
  }

  public void addGroup(String groupName) throws IOException{
    try{
      namenode.addGroup(groupName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }
  }

  public void addUserToGroup(String userName, String groupName) throws IOException{
    try{
      namenode.addUserToGroup(userName, groupName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }

    if(userName != null && groupName != null){
      for(ClientProtocol nn : allNNs) {
        try{
          if(!proxyEquals(nn,namenode)) {
            nn.invCachesUserAddedToGroup(userName, groupName);
          }
        }catch (RemoteException re){
          throw re.unwrapRemoteException();
        }
      }
    }
  }

  public void removeUser(String userName) throws IOException{
    try{
      namenode.removeUser(userName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }

    if(userName != null){
      for(ClientProtocol nn : allNNs) {
        try{
          if(!proxyEquals(nn,namenode)) {
            nn.invCachesUserRemoved(userName);
          }
        }catch (RemoteException re){
          throw re.unwrapRemoteException();
        }
      }
    }
  }

  public void removeGroup(String groupName) throws IOException{
    try{
      namenode.removeGroup(groupName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }

    if(groupName != null){
      for(ClientProtocol nn : allNNs) {
        try{
          if(!proxyEquals(nn,namenode)) {
            nn.invCachesGroupRemoved(groupName);
          }
        }catch (RemoteException re){
          throw re.unwrapRemoteException();
        }
      }
    }
  }

  public void removeUserFromGroup(String userName, String groupName) throws IOException{
    try{
      namenode.removeUserFromGroup(userName, groupName);
    }catch (RemoteException re){
      throw re.unwrapRemoteException();
    }

    if(userName != null && groupName != null){
      for(ClientProtocol nn : allNNs) {
        try{
          if(!proxyEquals(nn,namenode)) {
            nn.invCachesUserRemovedFromGroup(userName, groupName);
          }
        }catch (RemoteException re){
          throw re.unwrapRemoteException();
        }
      }
    }
  }

  @VisibleForTesting
  @InterfaceAudience.Private
  public void setNamenodes(Collection<ClientProtocol> namenodes){
    allNNs.clear();
    allNNs.addAll(namenodes);
  }

  TraceScope newPathTraceScope(String description, String path) {
    TraceScope scope = tracer.newScope(description);
    if (path != null) {
      scope.addKVAnnotation("path", path);
    }
    return scope;
  }

  TraceScope newSrcDstTraceScope(String description, String src, String dst) {
    TraceScope scope = tracer.newScope(description);
    if (src != null) {
      scope.addKVAnnotation("src", src);
    }
    if (dst != null) {
      scope.addKVAnnotation("dst", dst);
    }
    return scope;
  }

  Tracer getTracer() {
    return tracer;
  }

  public boolean isHDFSEncryptionEnabled() {
    return conf.get(
            DFSConfigKeys.DFS_ENCRYPTION_KEY_PROVIDER_URI, null) != null;
  }

  /**
   * Returns the SaslDataTransferClient configured for this DFSClient.
   *
   * @return SaslDataTransferClient configured for this DFSClient
   */
  public SaslDataTransferClient getSaslDataTransferClient() {
    return saslClient;
  }

  private boolean proxyEquals(ClientProtocol a, ClientProtocol b ){
    //only for unit testing
    if(! (a instanceof ProtocolTranslator) && !(b instanceof ProtocolTranslator)){
      return a.equals(b);
    }else{
      Client.ConnectionId id1 = RPC.getConnectionIdForProxy(a);
      Client.ConnectionId id2 = RPC.getConnectionIdForProxy(b);
      if(id1.getAddress().equals(id2.getAddress())){
        return true;
      }
      return false;
    }

  }

  private void setClientEpoch() throws IOException {
    if(leaderNN != null) {
      long startTime = System.currentTimeMillis();
      long epoch = leaderNN.getEpochMS();
      long endTime = System.currentTimeMillis();
      long diff = (endTime - startTime) / 2;
      Client.setEpoch(endTime, epoch + diff);
    }
  }

}

package org.apache.hadoop.hdfs.serverless.cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

/**
 * Maintains a cache that maps files to the particular serverless functions which cache that
 * file's (or directory's) metadata.
 *
 * Objects of this class are utilized by clients of Serverless HopsFS.
 */
public class FunctionMetadataMap {
    private static final Log LOG = LogFactory.getLog(FunctionMetadataMap.class);

    /**
     * Redis client. The mapping is stored in Redis so it can be accessed by both CLI and Java applications.
     */
    private final JedisPool redisPool;

    /**
     * The mapping is stored in here.
     *
     * This is presently not used; instead, we use a local Redis instance.
     */
    private final HashMap<String, Integer> cache;

    public FunctionMetadataMap(Configuration conf) {
        cache = new HashMap<String, Integer>();

        String host = conf.get(DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_ENDPOINT,
                DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_ENDPOINT_DEFAULT);

        int port = conf.getInt(DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_PORT,
                DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_PORT_DEFAULT);

        LOG.debug("Creating Redis client for host " + host + ", port " + port);

        redisPool = new JedisPool(host, port);
    }

    public FunctionMetadataMap() {
        cache = new HashMap<String, Integer>();

        String host = DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_ENDPOINT_DEFAULT;
        int port = DFSConfigKeys.SERVERLESS_METADATA_CACHE_REDIS_PORT_DEFAULT;

        LOG.debug("Creating Redis client for host " + host + ", port " + port);

        redisPool = new JedisPool(host, port);
    }

    /**
     * Check if the cache contains an entry for the particular file or directory.
     * @param path The path of the file or directory of interest.
     * @return `true` if the cache contains an entry for the given file or directory, otherwise `false`.
     */
    public boolean containsEntry(String path) {
        String pathToCache = getPathToCache(path);

        try (Jedis jedis = redisPool.getResource()) {
            return jedis.exists(pathToCache);
        }
    }

    /**
     * Return the particular serverless functions responsible for caching the metadata for the given file or directory.
     * @return the number of the associated serverless function, or -1 if no
     *         entry exists in the map for this function yet.
     */
    public int getFunction(String file) {
        /*if (cache.containsKey(file)) {
            int functionName = cache.get(file);
            LOG.debug(String.format("Returning function %s for file %s", file, functionName));
            return functionName;
        }
        else {
            LOG.debug("No entry associated with file " + file);
            return -1;
        }*/

        String pathToCache = getPathToCache(file);

        // LOG.debug("Checking path '" + pathToCache + "' for target '" + file + "'");

        try (Jedis jedis = redisPool.getResource()) {
            if (jedis.exists(pathToCache))
                return Integer.parseInt(jedis.get(pathToCache));
            else
                return -1;
        }
    }

    /**
     * Given the original path to a target file or directory, return the path that is used by the metadata map/cache.
     *
     * Essentially, this attempts to extract the parent directory's path. If it does not exist, then it makes sure
     * that the parameterized path is the root directory, since otherwise obtaining the parent directory should be
     * possible.
     *
     * @param originalPath The original path to target a file or directory.
     * @return The path that is used by the metadata map/cache.
     */
    private String getPathToCache(String originalPath) {
        // First, we get the parent of whatever file or directory is passed in, as we cache by parent directory.
        // Thus, if we received mapping info for /foo/bar, then we really have mapping info for anything of the form
        // /foo/*, where * is a file or terminal directory (e.g., "bar" or "bar/").
        Path parentPath = Paths.get(originalPath).getParent();
        String pathToCache = null;

        // If there is no parent, then we are caching metadata mapping information for the root.
        if (parentPath == null) {
            assert(originalPath.equals("/") || originalPath.isEmpty());
            pathToCache = originalPath;
        } else {
            pathToCache = parentPath.toString();
        }

        return pathToCache;
    }

    /**
     * Add an entry to the cache. Will not overwrite an existing entry unless parameter `overwriteExisting` is true.
     * @param path The file or directory (i.e., key) for which we are adding an entry to the cache.
     * @param function The serverless function (i.e., value) associated with the given file.
     * @param overwriteExisting Overwrite an existing entry.
     * @return `true` if entry was added to the cache, otherwise `false`.
     */
    public boolean addEntry(String path, int function, boolean overwriteExisting) {
        // Only add the file to the cache if we're supposed to overwrite existing entries or
        // if there does not already exist an entry for the given file/directory.
        /*if (overwriteExisting || !cache.containsKey(path)) {
            cache.put(path, function);
            return true;
        }*/

        String pathToCache = getPathToCache(path);

        LOG.debug("Adding cache entry with key '" + pathToCache + "' for target '" + path + "'. Value: " + function + ".");

        try (Jedis jedis = redisPool.getResource()) {
            String resp = jedis.set(pathToCache, String.valueOf(function));

            LOG.debug("Response from jedis.set('" + pathToCache + "', " + function + "): " + resp);
        }

        return false;
    }

    /**
     * Return the number of entries in the cache (i.e., the size of the cache).
     */
    public long size() {
        try (Jedis jedis = redisPool.getResource()) {
            return jedis.dbSize();
        }
        // return cache.size();
    }

    /**
     * Close the redis pool. Should be called on termination.
     */
    public void terminate() {
        redisPool.close();
    }
}

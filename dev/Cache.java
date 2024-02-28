/*
 * @file:   Cache.java
 * @author: Jacob Wang <tengdaw@andrew.cmu.edu>
 * A whole-file cache with an LRU eviction policy. 
 * The file in the cache is represented by an LRU node defined in Node.java
 */

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

class Cache {
    private long capacity;                                                 // maximum capacity of the cache in bytes
    private String cacheDir;                                               // cache root directory
    private Node head;                                                     // most recent node
    private Node tail;                                                     // least recent node
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<Node>> cache;  // cache content
    private long curSize;                                                  // sum of sizes for all file in the cache

    /**
     * Constructor to create a cache object
     * @param capacity  cache maximum capacity in bytes
     * @param cacheDir  cache root directory
     */
    public Cache(long capacity, String cacheDir) {
        this.capacity = capacity;
        this.cacheDir = cacheDir;
        cache = new ConcurrentHashMap<>();
        curSize = 0;
        head = new Node(); 
        tail = new Node();
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Get the most update-to-date readable proxy copy of a server file.
     * @param pathName the server file name
     * @return the node corresponding to the proxy file
     */
    public synchronized Node getReadableNode(String pathName) {
        Node res = null;
        if (cache.containsKey(pathName) && !cache.get(pathName).isEmpty()) {
            // read the latest version
            int maxVersion = -1;
            for (Node node: cache.get(pathName)) {
                if (node.readable && node.version > maxVersion) {
                    res = node;
                    maxVersion = node.version;
                }
            }
        }
        return res;
    }

    /**
     * Move a node to the front of the linked list. Called on every file access.
     * @param node file node
     */
    public synchronized void moveFront(Node node) {
        // remove from current position
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // add to the front
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Remove a node from the cache and delete the associated file from proxy
     * @param node the node to remove
     */
    public synchronized void removeNode(Node node) {
        // remove from linked list
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // remove cache entry
        cache.get(node.pathName).remove(node);
        // update cache size
        curSize -= node.size;
        // delete file from cache dir
        File file = new File(cacheDir + '/' + node.fileName);
        file.delete();
    }

    /**
     * Evict the least recently accessed file.
     * @return true if an eviction is performed, false if no files can be evicted
     */
    public synchronized boolean evictOne() {
        // traverse from least recent to most recent
        Node node = tail.prev;
        while (node != head) {
            if (node.isEvictable()) {
                removeNode(node);
                return true;
            }
            node = node.prev;
        }
        return false;
    }

    /**
     * Remove all stale copies of a file. A file node is stale if it
     * has an outdated version and is not used by any clients, i.e. refCnt = 0
     * @param pathName the file name on server
     */
    public synchronized void removeStaleCopy(String pathName) {
        if (cache.containsKey(pathName) && !cache.get(pathName).isEmpty()){
            for (Node node: cache.get(pathName))
                if (node.isEvictable())
                    removeNode(node);
        }
    }

    /**
     * Add a new file to the cache.
     * @param node the new file node
     * @return true if the file is successfully added, false if adding the file will exceed the capacity limit
     */
    public synchronized boolean addNode(Node node) {
        // evict first
        while (curSize + node.size > capacity) 
            if (!evictOne())
                return false;
        // add node to linked list
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
        // add cache entry
        if (!cache.containsKey(node.pathName)) 
            cache.put(node.pathName, new ConcurrentLinkedQueue<Node>());
        cache.get(node.pathName).add(node);
        // update cache size
        curSize += node.size;
        return true;
    }

    /**
     * Update the file size of a node. The cache size is updated accordingly
     * and cache eviction is performed if needed.
     * @param node    the file node
     * @param newSize the update file size
     * @return true if the file size is updated, false if exceeding capacity limit
     */
    public synchronized boolean updateNodeSize(Node node, long newSize) {
        long sizeDiff = newSize - node.size;
        while (curSize + sizeDiff > capacity)
            if (!evictOne())
                return false;
        curSize += sizeDiff;
        node.updateSize(newSize);
        moveFront(node);
        return true;
    }
}
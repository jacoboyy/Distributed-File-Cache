import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

class Cache {
    private long capacity;
    private String cacheDir;
    private Node head;
    private Node tail;
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<Node>> cache;
    private long curSize;

    public Cache(long capacity, String cacheDir) {
        this.capacity = capacity;
        this.cacheDir = cacheDir;
        cache = new ConcurrentHashMap<>();
        curSize = 0;
        head = new Node(); // most recent
        tail = new Node(); // least recent
        head.next = tail;
        tail.prev = head;
    }

    public synchronized Node getReadableNode(String pathName) {
        Node res = null;
        if (cache.containsKey(pathName) && !cache.get(pathName).isEmpty()) {
            // read the latest version
            int maxVersion = -1;
            for (Node node: cache.get(pathName)) {
                if (node.isReadable() && node.version > maxVersion) {
                    res = node;
                    maxVersion = node.version;
                }
            }
        }
        return res;
    }

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

    public synchronized void removeNode(Node node) {
        System.err.println("Evict file " + node.fileName + " from proxy");
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
        System.err.println("New cache size after removing node: " + curSize);
    }

    public synchronized boolean evictOne() {
        // traverse from LRU node
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

    public synchronized void removeStaleCopy(String pathName) {
        if (cache.containsKey(pathName) && !cache.get(pathName).isEmpty()){
            for (Node node: cache.get(pathName))
                if (node.isEvictable())
                    removeNode(node);
        }
    }

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
        System.err.println("New cache size after adding node: " + curSize);
        return true;
    }

    public synchronized boolean updateNodeSize(Node node, long newSize) {
        long sizeDiff = newSize - node.size;
        while (curSize + sizeDiff > capacity)
            if (!evictOne())
                return false;
        curSize += sizeDiff;
        node.updateSize(newSize);
        moveFront(node);
        System.err.println("New cache size after updating node: " + curSize);
        return true;
    }
}
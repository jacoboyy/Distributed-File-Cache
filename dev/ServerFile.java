/* communication protocol between proxy and server */
import java.io.Serializable;

public class ServerFile implements Serializable {
    // Actual file content
    public byte[] content;
    // Whether the file is valid
    public boolean valid;
    // Error number if the file is not valid
    public int errno;

    public ServerFile(byte[] content) {
        this.content = content;
        this.valid = true;
    }

    public ServerFile(int errno) {
        this.errno = errno;
        this.valid = false;
    }
}

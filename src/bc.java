/* 2018-01-14:
bc.java for BlockChain
Dr. Clark Elliott for CSC435

This is some quick sample code giving a simple framework for coordinating multiple processes in a blockchain group.

INSTRUCTIONS:

Set the numProceses class variable (e.g., 1,2,3), and use a batch file to match

AllStart.bat:

REM for three procesess:
start java bc 0
start java bc 1
java bc 2

You might want to start with just one process to see how it works.

Thanks: http://www.javacodex.com/Concurrency/PriorityBlockingQueue-Example

Notes to CDE:
Optional: send public key as Base64 XML along with a signed string.
Verfy the signature with public key that has been restored.

*/

import java.util.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

// CDE: Would normally keep a process block for each process in the multicast group:
//class ProcessBlock {
//    int processID;
//    PublicKey pubKey;
//    int port;
//    String IPAddress;
//}

// Class to keep track of the ports used by the different servers in the bc application.
class Ports {
    // starting point for port number scheme. Port numbers will be given out based on process ID.
    public static int KeyServerPortBase = 6050;
    public static int UnverifiedBlockServerPortBase = 6051;
    public static int BlockchainServerPortBase = 6052;

    // port to send and receive public keys to and from other processes
    public static int KeyServerPort;
    // port to send and receive unverified blocks
    public static int UnverifiedBlockServerPort;
    // port to send and receive blockchains
    public static int BlockchainServerPort;

    // assigning ports based on arbitrary base ports incremented by process ID x 1000
    public void setPorts() {
        KeyServerPort = KeyServerPortBase + (bc.PID * 1000);
        UnverifiedBlockServerPort = UnverifiedBlockServerPortBase + (bc.PID * 1000);
        BlockchainServerPort = BlockchainServerPortBase + (bc.PID * 1000);
    }
}

// worker thread that will read public keys from other bc processes (including this process)
class PublicKeyWorker extends Thread {
    // socket, passed in as argument. This will be a connection to another bc process
    Socket sock;

    // constructor, setting local reference to socket passed in
    PublicKeyWorker(Socket s) {
        sock = s;
    }

    public void run() {
        try {
            // creating reader to read data from socket (sent from other processes)
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            // reading in data from socket (which will be a public key)
            String data = in.readLine();
            // printing out public key to console
            System.out.println("Got key: " + data);
            // closing connection
            sock.close();
        } catch (IOException x) {
            x.printStackTrace();
        }
    }
}

class PublicKeyServer implements Runnable {
    //public ProcessBlock[] PBlock = new ProcessBlock[3]; // CDE: One block to store info for each process.

    public void run() {
        // setup
        int q_len = 6;
        // socket to receive public keys from other processes (including self)
        Socket sock;
        // printing port used to console
        System.out.println("Starting Key Server input thread using " + Integer.toString(Ports.KeyServerPort));
        try {
            // server socket to accept connections from processes trying to share their public keys
            ServerSocket servsock = new ServerSocket(Ports.KeyServerPort, q_len);
            while (true) {
                // waiting to accept a socket connection (will block here until a connection is received)
                sock = servsock.accept();
                // starting a PublicKeyWorker in a new thread.
                // This frees up the PublicKeyServer to accept new connections from other processes
                new PublicKeyWorker(sock).start();
            }
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
    }
}

// server to accept unverified blocks from other bc processes, including this process
class UnverifiedBlockServer implements Runnable {
    // thread-safe queue which will be passed in as argument.
    // This is where we will put the unverified blocks we receive.
    BlockingQueue<String> queue;

    // constructor. Setting local reference to the queue which will be passed in.
    UnverifiedBlockServer(BlockingQueue<String> queue) {
        this.queue = queue;
    }

    // Worker class that will be spawned in a different thread each time a new unverified block is received.
    // This worker will process the unverified block, freeing up the UnverifiedBlockServer to accept
    // new connections with new blocks
    class UnverifiedBlockWorker extends Thread {
        // Socket connection to another bc process (could be self).
        // This connection will be passed in from the UnverifiedBlockServer.
        // This is how we will receive the unverified block we are about to process.
        Socket sock;

        // local socket reference set in constructor
        UnverifiedBlockWorker(Socket s) {
            sock = s;
        }

        public void run() {
            try {
                // reader to read in unverified block from the socket
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                // reading in data
                String data = in.readLine();
                // printing data to console
                System.out.println("Put in priority queue: " + data + "\n");
                // putting data (unverified block) into queue
                queue.put(data);
                // closing connection
                sock.close();
            } catch (Exception x) {
                x.printStackTrace();
            }
        }
    }

    public void run() {
        // setup
        int q_len = 6;
        // local socket reference
        Socket sock;
        // printing UnverifiedBlockServer port to console
        System.out.println("Starting the Unverified Block Server input thread using " +
                Integer.toString(Ports.UnverifiedBlockServerPort));
        try {
            // creating server socket to accept connections from other bc processes sending unverified blocks
            // (other processes could be self)
            // port number retreived from previously set port numbers in Ports
            ServerSocket servsock = new ServerSocket(Ports.UnverifiedBlockServerPort, q_len);
            while (true) {
                // accepting an incoming connection from a bc process (will block here until connection is received)
                sock = servsock.accept();
                // starting an UnverifiedBlockWorker in a new thread and passing it the socket.
                // This allows the UnverifiedBlockServer to be freed up to accept new connections
                new UnverifiedBlockWorker(sock).start();
            }
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
    }
}

// Class which does the work of verifying unverified blocks.
// It gets these blocks from the thread-safe queue that it shares with the UnverifiedBlockServer.
class UnverifiedBlockConsumer implements Runnable {
    // thread safe queue, passed in through the constructor
    BlockingQueue<String> queue;
    int PID;

    UnverifiedBlockConsumer(BlockingQueue<String> queue) {
        this.queue = queue;
    }

    public void run() {
        String data;
        PrintStream toServer;
        Socket sock;
        String newblockchain;
        String fakeVerifiedBlock;

        System.out.println("Starting the Unverified Block Priority Queue Consumer thread.\n");
        try {
            while (true) {
                // take an unverified block from the shared queue
                data = queue.take();
                // print block to console
                System.out.println("Consumer got unverified: " + data);

                // Doing fake work
                int j;
                // trying to get the right number an arbitrary number of times (100)
                for (int i = 0; i < 100; i++) {
                    // picking a random number between 0 and 9
                    j = ThreadLocalRandom.current().nextInt(0, 10);
                    // sleeping for 5 seconds to simulate work being done
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    // if the random number was less than 3, we pretend that the puzzle has been solved
                    // and the block has been verified. Otherwise we try again with another random number.
                    if (j < 3) break;
                }
                // If we've reached this point it means we have a verified block to add to the blockchain.

                // if statement checks to see if data has already been added to the blockchain.
                // Doesn't always work but we don't care
                if (bc.blockchain.indexOf(data.substring(1, 9)) < 0) {
                    // creating the fake verified block to add to the blockchain from the data we just verified
                    fakeVerifiedBlock = "[" + data + " verified by P" + bc.PID + " at time "
                            + Integer.toString(ThreadLocalRandom.current().nextInt(100, 1000)) + "]\n";
                    // printing out verified block
                    System.out.println(fakeVerifiedBlock);
                    // adding new block to blockchain
                    String tempblockchain = fakeVerifiedBlock + bc.blockchain;
                    // sending out new blockchain to all bc processes, including self
                    for (int i = 0; i < bc.numProcesses; i++) {
                        // creating a socket to connect to each process
                        sock = new Socket(bc.serverName, Ports.BlockchainServerPortBase + (i * 1000));
                        // creating a print stream to write the data to the socket
                        toServer = new PrintStream(sock.getOutputStream());
                        // writing the new blockchain to the socket, sending it to the other processes
                        toServer.println(tempblockchain);
                        // flushing the socket connection to make sure data gets sent immediately
                        toServer.flush();
                        // closing connection
                        sock.close();
                    }
                }
                // pause for 1.5 seconds
                Thread.sleep(1500);
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

// Worker class to process blockchains received from other bc processes (including self)
class BlockchainWorker extends Thread {
    // Local socket reference. The socket is passed in through the constructor from the BlockchainServer.
    // The socket is a connection to a bc process.
    Socket sock;

    BlockchainWorker(Socket s) {
        sock = s;
    }

    public void run() {
        try {
            // Creating a reader to read in blockchain data from the socket
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String data = "";
            String data2;
            // reading in data from the socket and concatenating it into one string.
            // At the end of reading it will have read in the whole updated blockchain
            while ((data2 = in.readLine()) != null) {
                data = data + data2;
            }
            // setting the process's blockchain variable to this new updated blockchain
            bc.blockchain = data;
            // printing out the new blockchain to the console
            System.out.println("         --NEW BLOCKCHAIN--\n" + bc.blockchain + "\n\n");
            // closing the connection
            sock.close();
        } catch (IOException x) {
            x.printStackTrace();
        }
    }
}

// Server to accept blockchains from other bc processes (including self)
class BlockchainServer implements Runnable {
    public void run() {
        // setup
        int q_len = 6;
        // socket to receive blockchains through
        Socket sock;
        // printing out port number
        System.out.println("Starting the blockchain server input thread using " + Integer.toString(Ports.BlockchainServerPort));
        try {
            // creating server socket to accept connections from other bc processes (including self)
            ServerSocket servsock = new ServerSocket(Ports.BlockchainServerPort, q_len);
            while (true) {
                // accepting connection
                sock = servsock.accept();
                // starting BlockchainWorker in another thread, freeing up BlockchainServer to accept new connections.
                // Passing in socket to worker.
                new BlockchainWorker(sock).start();
            }
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
    }
}

@XmlRootElement
class BlockRecord {
    String SHA256String;
    String SignedSHA256;
    String BlockID;
    String VerificationProcessID;
    String CreatingProcess;
    String PreviousHash;
    String Fname;
    String Lname;
    String SSNum;
    String DOB;
    String Diag;
    String Treat;
    String Rx;

  /* CDE: A=header, F=Indentification, G=Medical */

    public String getASHA256String() {
        return SHA256String;
    }

    @XmlElement
    public void setASHA256String(String SH) {
        this.SHA256String = SH;
    }

    public String getASignedSHA256() {
        return SignedSHA256;
    }

    @XmlElement
    public void setASignedSHA256(String SH) {
        this.SignedSHA256 = SH;
    }

    public String getACreatingProcess() {
        return CreatingProcess;
    }

    @XmlElement
    public void setACreatingProcess(String CP) {
        this.CreatingProcess = CP;
    }

    public String getAVerificationProcessID() {
        return VerificationProcessID;
    }

    @XmlElement
    public void setAVerificationProcessID(String VID) {
        this.VerificationProcessID = VID;
    }

    public String getABlockID() {
        return BlockID;
    }

    @XmlElement
    public void setABlockID(String BID) {
        this.BlockID = BID;
    }

    public String getFSSNum() {
        return SSNum;
    }

    @XmlElement
    public void setFSSNum(String SS) {
        this.SSNum = SS;
    }

    public String getFFname() {
        return Fname;
    }

    @XmlElement
    public void setFFname(String FN) {
        this.Fname = FN;
    }

    public String getFLname() {
        return Lname;
    }

    @XmlElement
    public void setFLname(String LN) {
        this.Lname = LN;
    }

    public String getFDOB() {
        return DOB;
    }

    @XmlElement
    public void setFDOB(String DOB) {
        this.DOB = DOB;
    }

    public String getGDiag() {
        return Diag;
    }

    @XmlElement
    public void setGDiag(String D) {
        this.Diag = D;
    }

    public String getGTreat() {
        return Treat;
    }

    @XmlElement
    public void setGTreat(String D) {
        this.Treat = D;
    }

    public String getGRx() {
        return Rx;
    }

    @XmlElement
    public void setGRx(String D) {
        this.Rx = D;
    }

}

class DataFileToXmlParser {

    private static String FILENAME;

    private static final int iFNAME = 0;
    private static final int iLNAME = 1;
    private static final int iDOB = 2;
    private static final int iSSNUM = 3;
    private static final int iDIAG = 4;
    private static final int iTREAT = 5;
    private static final int iRX = 6;


    public String parse(int pnum) {

        int UnverifiedBlockPort;
        int BlockChainPort;

        UnverifiedBlockPort = 4710 + pnum;
        BlockChainPort = 4820 + pnum;

        System.out.println("Process number: " + pnum + " Ports: " + UnverifiedBlockPort + " " +
                BlockChainPort + "\n");

        switch (pnum) {
            case 1:
                FILENAME = "BlockInput1.txt";
                break;
            case 2:
                FILENAME = "BlockInput2.txt";
                break;
            default:
                FILENAME = "BlockInput0.txt";
                break;
        }

        System.out.println("Using input file: " + FILENAME);

        try {
            // try with resources block. Reader is created to read from medical data file.
            try (BufferedReader br = new BufferedReader(new FileReader("/Users/oliviachisman/Google Drive/depaul/csc_435/blockchain2/src/" + FILENAME))) {
                // setting up variables to be used when parsing medical data
                String[] tokens;
                String inputLineStr;
                String suuid;

                // array of blocks to be filled
                BlockRecord[] blockArray = new BlockRecord[20];

                // Setting up marshaller and helper classes. These will convert our XML string into something
                // that can be sent over the network to other processes.
                JAXBContext jaxbContext = JAXBContext.newInstance(BlockRecord.class);
                Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
                jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
                StringWriter sw = new StringWriter();

                // index to access block record array
                int n = 0;

                // reading in the medical data file line by line
                while ((inputLineStr = br.readLine()) != null) {
                    // create a new block record and add it to the block record array
                    blockArray[n] = new BlockRecord();

                    blockArray[n].setASHA256String("SHA string goes here..."); //TODO
                    blockArray[n].setASignedSHA256("Signed SHA string goes here..."); //TODO

                    // get universally unique identifier for the block record and set it in the block record
                    suuid = UUID.randomUUID().toString();
                    blockArray[n].setABlockID(suuid);
                    // set process number
                    blockArray[n].setACreatingProcess("Process" + pnum);
                    // we will set the verification id later during verification
                    blockArray[n].setAVerificationProcessID("To be set later...");
                    // setting the actual patient data. We know the data pieces will be separated by the string " +"
                    // and we know their ordering, as specified in the member variables above
                    tokens = inputLineStr.split(" +");
                    blockArray[n].setFSSNum(tokens[iSSNUM]);
                    blockArray[n].setFFname(tokens[iFNAME]);
                    blockArray[n].setFLname(tokens[iLNAME]);
                    blockArray[n].setFDOB(tokens[iDOB]);
                    blockArray[n].setGDiag(tokens[iDIAG]);
                    blockArray[n].setGTreat(tokens[iTREAT]);
                    blockArray[n].setGRx(tokens[iRX]);
                    // incrementing index
                    n++;
                }
                // printing out number of records and the names associated with them to the console
                System.out.println(n + " records read.");
                System.out.println("Names from input:");
                for (int i = 0; i < n; i++) {
                    System.out.println("  " + blockArray[i].getFFname() + " " +
                            blockArray[i].getFLname());
                }
                System.out.println("\n");

                // marshalling the whole block array into the string writer
                for (int i = 0; i < n; i++) {
                    jaxbMarshaller.marshal(blockArray[i], sw);
                }
                String fullBlock = sw.toString();
                System.out.println("----- FULL BLOCK ------");
                System.out.println(fullBlock);
                String XMLHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
                String cleanBlock = fullBlock.replace(XMLHeader, "");
                String XMLBlock = XMLHeader + "\n<BlockLedger>" + cleanBlock + "</BlockLedger>";
                System.out.println("----- FORMATTED BLOCK ------");
                System.out.println(XMLBlock);
                return XMLBlock;
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
}


public class bc {
    static String serverName = "localhost";
    static String blockchain = "[First block]";
    static int numProcesses = 3;
    static int PID = 0;

    // send out public key and unverified blocks to other bc processes
    public void MultiSend() {
        // this socket will be used to connect to servers in other processes
        Socket sock;
        // this print stream will be used to write data to other servers
        PrintStream toServer;

        try {
            // Send out public key to all bc processes' PublicKeyServers (including myself).
            // We can find their port numbers based on the port numbering scheme using their process IDs
            /** changing this later i think */
            for (int i = 0; i < numProcesses; i++) {
                sock = new Socket(serverName, Ports.KeyServerPortBase + (i * 1000));
                toServer = new PrintStream(sock.getOutputStream());
                toServer.println("FakeKeyProcess" + bc.PID);
                toServer.flush();
                sock.close();
            }
            /***/
            // pause to make sure all processes received the public keys
            Thread.sleep(1000);
            // send unverified blocks to all processes, including myself
            /** replace this with reading in data and creating unverified blocks and sending them out */
            String xml = new DataFileToXmlParser().parse(PID);
            /***/
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    public static void main(String args[]) {
        int q_len = 6; // setup
        // if argument present, parse it as the process ID. Otherwise, default to zero
        PID = (args.length < 1) ? 0 : Integer.parseInt(args[0]);
        // print user instructions and the process ID for this process
        System.out.println("Clark Elliott's BlockFramework control-c to quit.\n");
        System.out.println("Using processID " + PID + "\n");

        // Create a thread-safe queue to store unverified blocks.
        // The UnverifiedBlockServer will put blocks into this queue and the UnverifiedBlockConsumer will remove
        // blocks from this queue, hence the need for a thread-safe data structure.
        final BlockingQueue<String> queue = new PriorityBlockingQueue<>();
        // setting port numbers for PublicKeyServer, UnverifiedBlockServer, and BlockchainServer
        new Ports().setPorts();

        // starting up PublicKeyServer in a new thread
        new Thread(new PublicKeyServer()).start();
        // starting up UnverifiedBlockServer in a new thread, and passing in the queue created above
        new Thread(new UnverifiedBlockServer(queue)).start();
        // starting up BlockchainServer in a new thread
        new Thread(new BlockchainServer()).start();
        // pause for 5 seconds to make sure other bc processes have started
        try {
            Thread.sleep(5000);
        } catch (Exception e) {
        }
        // send out public key, unverified blocks
        new bc().MultiSend();
        // pause for 1 second to make sure all processes received data
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
        }

        // start up UnverifiedBlockConsumer in a new thread
        new Thread(new UnverifiedBlockConsumer(queue)).start();
    }
}
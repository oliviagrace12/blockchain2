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

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

// CDE: Would normally keep a process block for each process in the multicast group:
class ProcessBlock {
    int processID;
    PublicKey pubKey;
    int port;
    String IPAddress;

    public ProcessBlock(int processID, PublicKey pubKey, int port, String IPAddress) {
        this.processID = processID;
        this.pubKey = pubKey;
        this.port = port;
        this.IPAddress = IPAddress;
    }

    public ProcessBlock(int processID, PublicKey pubKey) {
        this.processID = processID;
        this.pubKey = pubKey;
        this.port = 0;
        this.IPAddress = "";
    }

    public int getProcessID() {
        return processID;
    }

    public PublicKey getPubKey() {
        return pubKey;
    }
}

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
        KeyServerPort = KeyServerPortBase + (Blockchain.PID * 1000);
        UnverifiedBlockServerPort = UnverifiedBlockServerPortBase + (Blockchain.PID * 1000);
        BlockchainServerPort = BlockchainServerPortBase + (Blockchain.PID * 1000);
    }
}

// worker thread that will read public keys from other bc processes (including this process)
class PublicKeyWorker extends Thread {
    // socket, passed in as argument. This will be a connection to another bc process
    Socket sock;
    ProcessBlock[] processBlocks;

    // constructor, setting local reference to socket passed in
    PublicKeyWorker(Socket s, ProcessBlock[] processBlocks) {
        sock = s;
        this.processBlocks = processBlocks;
    }

    // I got the idea for how to send public keys from
    // https://stackoverflow.com/questions/7733270/java-public-key-different-after-sent-over-socket
    // TODO change this to use XML (once figure it out for other part) -- no
    public void run() {
        try {
            // creating reader to read data from socket (sent from other processes)
            ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
            // reading in object from socket (which will be a public key wrapper)
            PublicKeyWrapper wrapper = (PublicKeyWrapper) in.readObject();
            // getting process id of public key from wrapper
            int pidForPubKey = wrapper.getpID();
            // getting the byte array that will be converted back into a public key
            byte[] pubKeyBytes = wrapper.getBytes();
            // converting the byte array back into a public key
            // (source: https://stackoverflow.com/questions/7733270/java-public-key-different-after-sent-over-socket)
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubKeyBytes));
            // printing out public key to console
            System.out.println("Got key: " + publicKey);
            // storing public key info in a process block, which will be stored in an array
            ProcessBlock processBlock = new ProcessBlock(pidForPubKey, publicKey);
            processBlocks[pidForPubKey] = processBlock;
            // closing connection
            sock.close();
        } catch (IOException | ClassNotFoundException | NoSuchAlgorithmException | InvalidKeySpecException x) {
            x.printStackTrace();
        }
    }
}

class PublicKeyServer implements Runnable {
    public ProcessBlock[] processBlocks = new ProcessBlock[3]; // CDE: One block to store info for each process.

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
                new PublicKeyWorker(sock, processBlocks).start();
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
                StringBuffer buffer = new StringBuffer();
                String data = in.readLine();
                while (data != null) {
                    buffer.append(data);
                    data = in.readLine();
                }
//                String xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
//                String[] blockXmls = buffer.toString().split(xmlHeader);

//                for (int i = 0; i < blockXmls.length; i++) {
//                    String blockXml = xmlHeader + blockXmls[i];
                // printing data to console

                String allData = buffer.toString();
                System.out.println("Put in priority queue: " + allData + "\n");
                // putting data (unverified block) into queue
                queue.put(allData);
//                }

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
    KeyPair keyPair;

    UnverifiedBlockConsumer(BlockingQueue<String> queue, KeyPair keyPair) {
        this.queue = queue;
        this.keyPair = keyPair;
    }

    public void run() {
        String unverifiedBlock;
        PrintStream toServer;
        Socket sock;
        String newblockchain;
        String fakeVerifiedBlock;

        System.out.println("Starting the Unverified Block Priority Queue Consumer thread.\n");
        try {
            while (true) {
                // take an unverified block from the shared queue
                unverifiedBlock = queue.take();
                // print block to console
                System.out.println("Consumer got unverified: " + unverifiedBlock);

                // Doing fake work
                int j = 0;
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

                // converting the unverified xml block to a BlockRecord object
                BlockRecord newBlockRecord = getBlockRecordFromBlockXml(unverifiedBlock);

                // check to see if data has already been added to the blockchain.
                if (!Blockchain.blockchain.contains(newBlockRecord.getABlockID())) {
                    // setting the verified by field
                    newBlockRecord.setAVerificationProcessID("" + Blockchain.PID);
                    // setting the seed field
                    newBlockRecord.setASeed("" + j);
                    // converting back to XML
                    String newBlockXmlWithSeed = getXmlFromBlockRecord(newBlockRecord);

                    // gather things to put into new hash: previous blockchain hash and new block data including seed
                    String stuffToHash = getPreviousHash(Blockchain.blockchain) + newBlockXmlWithSeed;
                    // create hash
                    String sha256String = hashData(stuffToHash);
                    // sign hex string
                    byte[] signedSHA256bytes = signData(sha256String.getBytes(), keyPair.getPrivate());
                    // convert to string
                    String signedSHA256 = convertToString(signedSHA256bytes);

                    // inserting the hashes into the new block
                    newBlockRecord.setASHA256String(sha256String);
                    newBlockRecord.setASignedSHA256(signedSHA256);

                    // convert verified BlockRecord to XML
                    String verifiedBlock = getXmlFromBlockRecord(newBlockRecord);

                    System.out.println("---- New verified block from PID " + Blockchain.PID + " -----");
                    System.out.println(verifiedBlock);

                    // adding new block to blockchain
                    String newBlockchain = verifiedBlock + Blockchain.blockchain;
                    // sending out new blockchain to all bc processes, including self
                    for (int i = 0; i < Blockchain.numProcesses; i++) {
                        // creating a socket to connect to each process
                        sock = new Socket(Blockchain.serverName, Ports.BlockchainServerPortBase + (i * 1000));
                        // creating a print stream to write the data to the socket
                        toServer = new PrintStream(sock.getOutputStream());
                        // writing the new blockchain to the socket, sending it to the other processes
                        toServer.println(newBlockchain);
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
            e.printStackTrace();
        }
    }

    // converting BlockRecord back into XML
    private String getXmlFromBlockRecord(BlockRecord blockRecord) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(BlockRecord.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        StringWriter sw = new StringWriter();
        jaxbMarshaller.marshal(blockRecord, sw);
        return sw.toString();
    }

    private BlockRecord getBlockRecordFromBlockXml(String xml) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(BlockRecord.class);
        Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
        StringReader reader = new StringReader(xml);

        return (BlockRecord) jaxbUnmarshaller.unmarshal(reader);
    }

    private String getPreviousHash(String blockchain) {
//        System.out.println("Current blockchain: ");
//        System.out.println(blockchain);
        String str = blockchain.substring(blockchain.indexOf("<ASHA256String>"), blockchain.indexOf("</ASHA256String>"));
        str = str.replace("<ASHA256String>", "");
        str = str.replace("</ASHA256String>", "");
        return str;
    }

    public static byte[] signData(byte[] data, PrivateKey key) throws Exception {
        // sign data using SHA1 with RSA algorithm
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }

    public String convertToString(byte[] byteData) {
        return Base64.getEncoder().encodeToString(byteData);
    }

    public String hashData(String unverifiedBlock) throws NoSuchAlgorithmException {
        // convert to SHA-256 array of bytes
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(unverifiedBlock.getBytes());
        byte[] byteData = md.digest();

        return convertToString(byteData);
    }

}

// Worker class to process blockchains received from other bc processes (including self)
class BlockchainWorker extends Thread {
    // Local socket reference. The socket is passed in through the constructor from the BlockchainServer.
    // The socket is a connection to a bc process.
    Socket sock;
    ProcessBlock[] processBlocks;

    BlockchainWorker(Socket s, ProcessBlock[] processBlocks) {
        sock = s;
        this.processBlocks = processBlocks;
    }

    public void run() {
        try {
            // Creating a reader to read in blockchain data from the socket
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

            // reading in data from the socket and concatenating it into one string.
            // At the end of reading it will have read in the whole updated blockchain
            StringBuilder newBlockchainSb = new StringBuilder();
            String data;
            while ((data = in.readLine()) != null) {
                newBlockchainSb.append(data);
            }
            String newBlockchain = newBlockchainSb.toString();

            // get hash from newest blockchain block
            String hash = getFirstOfElementFromBlockchain(newBlockchain, "ASHA256String");
            // get signed hash from newest blockchain
            String signedHash = getFirstOfElementFromBlockchain(newBlockchain, "ASignedSHA256");
            // get verification process id from newest blockchain block
            int verifierPid = Integer.valueOf(getFirstOfElementFromBlockchain(newBlockchain, "AVerificationProcessID"));
            // look up public key of this process by its process id
            PublicKey verifierPubKey = processBlocks[verifierPid].pubKey;

            // verify the signature using the processes public key to decrypt the signed hash and compare it to the original hash
            boolean verifiedSig = verifySig(hash.getBytes(), verifierPubKey, Base64.getDecoder().decode(signedHash));

            // if signature has been verified, print the new blockchain to the console and set it as the current blockchain
            // otherwise, discard the new blockchain
            if (verifiedSig) {
                System.out.println("Verified signature of new blockchain from verifierPid");
                // setting the process's blockchain variable to this new updated blockchain
                Blockchain.blockchain = newBlockchain;
                // printing out the new blockchain to the console
                System.out.println("         -- NEW BLOCKCHAIN --\n" + Blockchain.blockchain + "\n\n");
                if (Blockchain.PID == 0) {
                    System.out.println("Writing new blockchain to file");
                    // writing blockchain to file
                    File file = new File("./BlockchainLedger.xml");
                    FileWriter writer = new FileWriter(file);
                    writer.write(Blockchain.blockchain);
                    writer.flush();
                    writer.close();
                    Blockchain.file = file;
                }
            } else {
                System.out.println("Could not verify signature of new blockchain. Discarding");
            }

            // closing the connection
            sock.close();
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    // verify the signature using the processes public key to decrypt the signed hash and compare it to the original hash
    public static boolean verifySig(byte[] data, PublicKey key, byte[] sig) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initVerify(key);
        signer.update(data);

        return (signer.verify(sig));
    }

    // extract the value of a given element from the blockchain and return it as a string
    private String getFirstOfElementFromBlockchain(String blockchain, String element) {
        String str = blockchain.substring(blockchain.indexOf("<" + element + ">"), blockchain.indexOf("</" + element + ">"));
        str = str.replace("<" + element + ">", "");
        str = str.replace("</" + element + ">", "");
        return str;
    }
}

// Server to accept blockchains from other bc processes (including self) and verify their source using other
// processes' public keys
class BlockchainServer implements Runnable {

    private ProcessBlock[] processBlocks;

    public BlockchainServer(ProcessBlock[] processBlocks) {
        this.processBlocks = processBlocks;
    }

    public void run() {
        // setup
        int q_len = 6;
        // socket to receive blockchains through
        Socket sock;
        // printing out port number
        System.out.println("Starting the blockchain server input thread using "
                + Integer.toString(Ports.BlockchainServerPort));

        try {
            // creating server socket to accept connections from other bc processes (including self)
            ServerSocket servsock = new ServerSocket(Ports.BlockchainServerPort, q_len);
            while (true) {
                // accepting connection
                sock = servsock.accept();
                // starting BlockchainWorker in another thread, freeing up BlockchainServer to accept new connections.
                // Passing in socket to worker.
                new BlockchainWorker(sock, processBlocks).start();
            }
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
    }
}

//
@XmlRootElement
class BlockRecord {
    String SHA256String;
    String SignedSHA256;
    String BlockID;
    String VerificationProcessID;
    String CreatingProcess;
    String PreviousHash;
    String Seed;
    String Fname;
    String Lname;
    String SSNum;
    String DOB;
    String Diag;
    String Treat;
    String Rx;

    /* CDE: A=header, F=Indentification, G=Medical */

    public String getAPreviousHash() {
        return PreviousHash;
    }

    public void setAPreviousHash(String previousHash) {
        PreviousHash = previousHash;
    }

    public String getASeed() {
        return Seed;
    }

    public void setASeed(String seed) {
        Seed = seed;
    }

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


    public List<String> parse(int pnum) {

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
            try (BufferedReader br = new BufferedReader(new FileReader(FILENAME))) {
                // setting up variables to be used when parsing medical data
                String[] tokens;
                String inputLineStr;
                String suuid;

                // array of blocks to be filled
                BlockRecord[] blockArray = new BlockRecord[20];

                // Setting up marshaller and helper classes. These will convert our BlockRecords into XML strings
                // that can be sent over the network to other processes.
                JAXBContext jaxbContext = JAXBContext.newInstance(BlockRecord.class);
                Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
                jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);


                // index to access block record array
                int n = 0;

                // reading in the medical data file line by line
                while ((inputLineStr = br.readLine()) != null) {
                    // create a new block record and add it to the block record array
                    blockArray[n] = new BlockRecord();

                    blockArray[n].setASHA256String("");
                    blockArray[n].setASignedSHA256("");

                    // get universally unique identifier for the block record and set it in the block record
                    suuid = UUID.randomUUID().toString();
                    blockArray[n].setABlockID(suuid);
                    // set process number
                    blockArray[n].setACreatingProcess("" + pnum);
                    // we will set the verification id later during verification
                    blockArray[n].setAVerificationProcessID("");
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

                List<String> xmlBlocks = new ArrayList<>();
                // converting each element of the BlockWriter array into XML and putting it into the StringWriter
                StringWriter sw;
                for (int i = 0; i < n; i++) {
                    sw = new StringWriter();
                    jaxbMarshaller.marshal(blockArray[i], sw);
                    String xmlBlock = sw.toString();
                    System.out.println("--- Created unverified block ------");
                    System.out.println(xmlBlock);
                    xmlBlocks.add(xmlBlock);
                }
//                // String containing each block converted to XML
//                String fullBlock = sw.toString();
//
//                // formatting string to make one single XML out of many
//                // (only one header for all blocks instead of a header for each)
//                String XMLHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
//                String cleanBlock = fullBlock.replace(XMLHeader, "");
//                String XMLBlock = XMLHeader + "\n<BlockLedger>" + cleanBlock + "</BlockLedger>";

                return xmlBlocks;
//                return fullBlock;
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }
}

// got idea for this from https://stackoverflow.com/questions/7733270/java-public-key-different-after-sent-over-socket
// Allows me to send the public key over a socket.
class PublicKeyWrapper implements Serializable {
    private byte[] bytes;
    private int pID;

    public PublicKeyWrapper(byte[] bytes, int pID) {
        this.bytes = bytes;
        this.pID = pID;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int getpID() {
        return pID;
    }
}

public class Blockchain {
    static String serverName = "localhost";
    static String blockchain = "[First block]";
    static int numProcesses = 3;
    static int PID = 0;
    static KeyPair keyPair;
    static File file;

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

        PublicKeyServer publicKeyServer = new PublicKeyServer();

        // starting up PublicKeyServer in a new thread
        new Thread(publicKeyServer).start();
        // starting up UnverifiedBlockServer in a new thread, and passing in the queue created above
        new Thread(new UnverifiedBlockServer(queue)).start();
        // starting up BlockchainServer in a new thread
        new Thread(new BlockchainServer(publicKeyServer.processBlocks)).start();
        // pause for 5 seconds to make sure other bc processes have started
        try {
            Thread.sleep(5000);
        } catch (Exception e) {
        }

//        System.out.println("*** init ***");
//        System.out.println(blockchain);

        // create initial block
        createAndSendFirstBlock();
//        System.out.println("*** init2 ***");
//        System.out.println(blockchain);


        // send out public key, unverified blocks
        new Blockchain().MultiSend();
        // pause for 1 second to make sure all processes received data
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
        }

        // start up UnverifiedBlockConsumer in a new thread
        new Thread(new UnverifiedBlockConsumer(queue, keyPair)).start();
    }


    // generating an RSA public-private key pair
    private KeyPair generateKeyPair(long seed) throws Exception {
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
        SecureRandom rng = SecureRandom.getInstance("SHA1PRNG", "SUN");
        rng.setSeed(seed);
        keyGenerator.initialize(1024, rng);

        return (keyGenerator.generateKeyPair());
    }

    // send out public key and unverified blocks to other bc processes
    private void MultiSend() {

        try {
            // generate key pair and send out public key to all processes, including self
            sendOutPublicKey();

            // pause to make sure all processes received the public keys
            Thread.sleep(1000);

            // parse unverified blocks from given medical data and them out to all processes, including self
            sendOutUnverifiedBlocks();

        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    public void sendOutUnverifiedBlocks() throws IOException {
        // parse data file for this process into XML
        List<String> xmls = new DataFileToXmlParser().parse(PID);

        for (String xml : xmls) {
            System.out.println("--- Sending out unverified block -----");
            System.out.println(xml);

            // this socket will be used to connect to servers in other processes
            Socket sock;
            // sending out unverified block to all bc processes, including self
            PrintStream toServer_block;

            for (int i = 0; i < Blockchain.numProcesses; i++) {
                // creating a socket to connect to each process
                sock = new Socket(Blockchain.serverName, Ports.UnverifiedBlockServerPortBase + (i * 1000));
                // creating a print stream to write the data to the socket
                toServer_block = new PrintStream(sock.getOutputStream());
                // writing the new blockchain to the socket, sending it to the other processes
                toServer_block.println(xml);
                // flushing the socket connection to make sure data gets sent immediately
                toServer_block.flush();
                // closing connection
                sock.close();
            }
        }
    }

    public void sendOutPublicKey() throws Exception {
        // generate a public and private key using a random seed
        keyPair = generateKeyPair(new Random().nextInt());

        // this socket will be used to connect to servers in other processes
        Socket sock;
        // this print stream will be used to write data to other servers
        ObjectOutputStream toServer_keys;
        // generate public and private keys for this process

        // Send out public key to all bc processes' PublicKeyServers (including myself).
        for (int i = 0; i < numProcesses; i++) {
            // creating a socket to connect to each process, including myself
            sock = new Socket(serverName, Ports.KeyServerPortBase + (i * 1000));
            toServer_keys = new ObjectOutputStream(sock.getOutputStream());
            toServer_keys.writeObject(new PublicKeyWrapper(keyPair.getPublic().getEncoded(), PID));
            toServer_keys.flush();
            sock.close();
        }
    }

    public static void createAndSendFirstBlock() {
        try {
            // create initial blockchain
            BlockRecord init = new BlockRecord();
            // get universally unique identifier for the block record and set it in the block record
            init.setABlockID(UUID.randomUUID().toString());
            // set process number
            init.setACreatingProcess("" + PID);

            // Setting up marshaller and helper classes. These will convert our BlockRecords into XML strings
            // that can be sent over the network to other processes.
            JAXBContext jaxbContext = JAXBContext.newInstance(BlockRecord.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            StringWriter sw = new StringWriter();

            jaxbMarshaller.marshal(init, sw);
            String xmlBlock = sw.toString();

            // create hash
            String sha256String = hashData(xmlBlock);

            // inserting the hashes into the new block
            init.setASHA256String(sha256String);
            init.setASignedSHA256(sha256String);

            // convert completed block record to XML
            StringWriter sw2 = new StringWriter();
            jaxbMarshaller.marshal(init, sw2);

            String xml = sw2.toString();

//            System.out.println("*** Setting init ***");
//            System.out.println(xml);

            // set initial blockchain
            Blockchain.blockchain = xml;
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    public static String convertToHex(byte[] byteData) {
        return Base64.getEncoder().encodeToString(byteData);
    }

    public static String hashData(String unverifiedBlock) throws NoSuchAlgorithmException {
        // convert to SHA-256 array of bytes
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(unverifiedBlock.getBytes());
        byte[] byteData = md.digest();

        return convertToHex(byteData);
    }
}
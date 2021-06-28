package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Address;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 *  peer create command
 *
 *		convex.peer.create
 *
 *  This creates an account and provides enougth funds, for a new peer account
 *
 *
 */

@Command(name="create",
	aliases={"cr"},
	mixinStandardHelpOptions=true,
	description="Creates a keypair, new account and a funding stake: to run a local peer.")
public class PeerCreate implements Runnable {

	private static final Logger log = Logger.getLogger(PeerCreate.class.getName());

	@ParentCommand
	private Peer peerParent;

	@Spec CommandSpec spec;

	@Option(names={"-i", "--index"},
		defaultValue="-1",
		description="Keystore index of the public/private key to use for the peer.")
	private int keystoreIndex;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use for the peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"--port"},
		description="Port number of nearest peer to connect too.")
	private int port = 0;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;


	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;
//		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());

		int port = 0;
		long peerStake = 10000000000L;

		AKeyPair keyPair = null;
		KeyStore keyStore;

		try {
			// create a keystore if it does not exist
			keyStore = mainParent.loadKeyStore(true);
		} catch (Error e) {
			log.info(e.getMessage());
			return;
		}

		try {
			keyPair = AKeyPair.generate();

			// save the new keypair in the keystore
			PFXTools.saveKey(keyStore, keyPair, mainParent.getPassword());

			File keyFile = new File(mainParent.getKeyStoreFilename());

			// save the store to a file
			PFXTools.saveStore(keyStore, keyFile, mainParent.getPassword());

			// connect using the default first user
			Convex convex = mainParent.connectToSessionPeer(
				hostname,
				port,
				Main.initConfig.getUserAddress(0),
				Main.initConfig.getUserKeyPair(0));

			// create an account
			Address address = convex.createAccount(keyPair.getAccountKey());
			convex.transferSync(address, peerStake);

			convex = mainParent.connectToSessionPeer(hostname, port, address, keyPair);
			long stakeBalance = convex.getBalance(address);

			System.out.println("Created the following items:");
			System.out.println("Keypair: " + keyPair.getAccountKey());
			System.out.println("Account address: "+ address);
			System.out.println("Stake balance of: " + stakeBalance);

		} catch (Throwable t) {
			System.out.println("Unable to launch peer "+t);
		}
	}
}
package convex.core.init;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;

public class InitConfig  extends AInitConfig {

	public static int DEFAULT_PEER_COUNT = 8;
	public static int DEFAULT_USER_COUNT = 2;


	protected InitConfig(AKeyPair userKeyPairs[], AKeyPair peerKeyPairs[]) {
		this.userKeyPairs = userKeyPairs;
		this.peerKeyPairs = peerKeyPairs;
	}

	public static InitConfig create() {
		return create(DEFAULT_USER_COUNT, DEFAULT_PEER_COUNT);
	}

	public static InitConfig create(int userCount, int peerCount) {
		AKeyPair userKeyPairs[] = new Ed25519KeyPair[userCount];
		AKeyPair peerKeyPairs[] = new Ed25519KeyPair[peerCount];

		for (int i = 0; i < userCount; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(543212345 + i);
			userKeyPairs[i] = kp;
		}

		for (int i = 0; i < peerCount; i++) {
			AKeyPair kp = Ed25519KeyPair.createSeeded(123454321 + i);
			peerKeyPairs[i] = kp;
		}

		return new InitConfig(userKeyPairs, peerKeyPairs);
	}
}
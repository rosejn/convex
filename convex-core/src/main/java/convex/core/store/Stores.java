package convex.core.store;

import java.util.logging.Level;

import etch.EtchStore;

public class Stores {

	// Default store
	private static AStore defaultStore=null;
	
	// Configured global store
	private static AStore globalStore=null;
	
	// Thread local current store, in case servers want different stores
	private static final ThreadLocal<AStore> currentStore = new ThreadLocal<>() {
		@Override
		protected AStore initialValue() {
			return getGlobalStore();
		}
	};

	/**
	 * Logging level for store persistence. 
	 */
	public static final Level PERSIST_LOG_LEVEL = Level.FINE;
	public static final Level STORE_LOG_LEVEL = Level.FINE;
	

	/**
	 * Gets the current (thread-local) Store instance
	 * 
	 * @return Store for the current thread
	 */
	public static AStore current() {
		return Stores.currentStore.get();
	}

	/**
	 * Sets the current thread-local store for this thread
	 * 
	 * @param store Any AStore instance
	 */
	public static void setCurrent(AStore store) {
		currentStore.set(store);
	}
	
	public static AStore getDefaultStore() {
		if (defaultStore==null) {
			defaultStore=EtchStore.createTemp("convex-db");;
		}
		return defaultStore;
	}

	public static AStore getGlobalStore() {
		if (globalStore==null) {
			globalStore=getDefaultStore();
		}
		return globalStore;
	}

	public static void setGlobalStore(EtchStore store) {
		globalStore=store;
	}
}

package com.hometv.tv.testing

/**
 * Every case whose write replaces an existing DataStore file (`DataStoreSessionStoreTest`, `EnginePrefsTest`): Windows refuses
 * DataStore's rename of the `.tmp` file over it. They run on macOS/Linux.
 */
const val WINDOWS_DATASTORE_RENAME = "DataStore can't rename its .tmp over an existing file on Windows"

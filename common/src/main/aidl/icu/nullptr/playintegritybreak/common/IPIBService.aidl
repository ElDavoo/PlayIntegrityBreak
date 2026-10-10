package icu.nullptr.playintegritybreak.common;

import icu.nullptr.playintegritybreak.common.IIntegrityCheckCallback;

interface IPIBService {

    void writeConfig(String json) = 0;

    int getServiceVersion() = 1;

    long getServiceHealthcheckTimestamp() = 10;

    int getFilterCount() = 2;

    String getLogs() = 3;

    void clearLogs() = 4;

    String readConfig() = 5;

    void log(int level, String tag, String message) = 6;

    String[] getPackageNames(int userId) = 7;

    PackageInfo getPackageInfo(String packageName, int userId) = 8;

    String getLogFileLocation() = 9;

    String getBackendName() = 11;

    /**
     * Reads the Play Integrity verdict from the Play Store inside the Play Store process, without UI
     * (see PlayStoreIntegrityCheck). The result is PLAY_STORE_RESULT_OK with the verdict JSON, or
     * PLAY_STORE_RESULT_FAILED with the reason. Transaction 12 is retired (it was the checker app's check).
     */
    void runPlayStoreIntegrityCheck(IIntegrityCheckCallback callback) = 13;

}

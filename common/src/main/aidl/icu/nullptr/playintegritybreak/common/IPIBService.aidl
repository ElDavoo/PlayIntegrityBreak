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
     * Asks the Play Integrity API Checker app to run an integrity check (see CheckerMonitorHook).
     * The Play Store holds the DUMP permission that the checker's trigger receiver requires,
     * so PIB does not need root for this.
     */
    void runIntegrityCheck(IIntegrityCheckCallback callback) = 12;

}

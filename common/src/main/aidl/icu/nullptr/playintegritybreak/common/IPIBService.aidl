package icu.nullptr.playintegritybreak.common;

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

}

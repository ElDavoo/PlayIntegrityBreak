package icu.nullptr.playintegritybreak.common;

interface IPIBService {

    void stopService(boolean cleanEnv) = 0;

    void writeConfig(String json) = 1;

    int getServiceVersion() = 2;

    int getFilterCount() = 3;

    String getLogs() = 4;

    void clearLogs() = 5;

    String readConfig() = 6;

    void log(int level, String tag, String message) = 7;

    String[] getPackageNames(int userId) = 8;

    PackageInfo getPackageInfo(String packageName, int userId) = 9;

    String getLogFileLocation() = 10;
}

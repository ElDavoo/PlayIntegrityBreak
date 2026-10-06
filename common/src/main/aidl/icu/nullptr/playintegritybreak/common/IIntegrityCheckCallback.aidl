package icu.nullptr.playintegritybreak.common;

/** Result of IPIBService.runIntegrityCheck: the checker broadcast's result code and data. */
oneway interface IIntegrityCheckCallback {

    void onResult(int resultCode, String resultData) = 0;

}

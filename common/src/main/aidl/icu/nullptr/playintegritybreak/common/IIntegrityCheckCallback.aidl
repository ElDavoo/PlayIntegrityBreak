package icu.nullptr.playintegritybreak.common;

/** Result of IPIBService.runPlayStoreIntegrityCheck: a PLAY_STORE_RESULT_* code and the verdict JSON or the reason. */
oneway interface IIntegrityCheckCallback {

    void onResult(int resultCode, String resultData) = 0;

}

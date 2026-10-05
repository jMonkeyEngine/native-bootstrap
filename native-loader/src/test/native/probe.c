#include <jni.h>
JNIEXPORT jint JNICALL Java_com_jme3_nativebootstrap_loader_NativeLoaderIntegrationTest_answer(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 42;
}

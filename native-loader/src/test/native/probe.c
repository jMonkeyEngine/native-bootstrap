#include <jni.h>
static jint load_count;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    ++load_count;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_com_jme3_nativebootstrap_loader_NativeLoaderIntegrationTest_loadCount(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return load_count;
}

JNIEXPORT jint JNICALL Java_com_jme3_nativebootstrap_loader_NativeLoaderIntegrationTest_answer(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 42;
}

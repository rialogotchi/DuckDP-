#include <jni.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern int ciadpi_main(int argc, char **argv);

/* Runs ByeDPI inside the (separate) ":dpi" process. Blocks until the proxy exits.
 * stderr is redirected to a log file so the UI can show it. */
JNIEXPORT jint JNICALL
Java_org_duckdns_dpi_android_NativeProxy_run(JNIEnv *env, jclass cls, jobjectArray jargs, jstring jlog)
{
    int n = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc((size_t)n + 2, sizeof(char *));
    argv[0] = strdup("ciadpi");
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    if (jlog != NULL) {
        const char *p = (*env)->GetStringUTFChars(env, jlog, NULL);
        if (freopen(p, "w", stderr) != NULL) {
            setvbuf(stderr, NULL, _IOLBF, 0);
        }
        (*env)->ReleaseStringUTFChars(env, jlog, p);
    }
    optind = 1;
    return ciadpi_main(n + 1, argv);
}

# Keep ARCore classes intact — R8 must not rename or remove them
-keep class com.google.ar.** { *; }

# Keep NIO FileChannel + StandardOpenOption used in PlyExporter/StlExporter
-keep class java.nio.channels.FileChannel { *; }
-keep class java.nio.file.StandardOpenOption { *; }

# Keep SparseIntArray (Android framework; normally safe but belt-and-suspenders)
-keep class android.util.SparseIntArray { *; }

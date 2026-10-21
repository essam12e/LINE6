# Shrink only: keep every app class (manifest components, reflection-free code), drop unused library code.
-dontobfuscate
-dontoptimize
-dontpreverify
-dontnote
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class sa.nitaaq.mirror.** { *; }

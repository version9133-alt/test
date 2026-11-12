# Camera2 callbacks are invoked reflectively by the framework only through interfaces we implement,
# so default R8 rules are sufficient. Keep line numbers for readable crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

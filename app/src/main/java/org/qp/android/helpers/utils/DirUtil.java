package org.qp.android.helpers.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.Locale;

public final class DirUtil {

    private static final String TAG = DirUtil.class.getSimpleName();

    /**
     * If there is only one folder in the folder and nothing else,
     * recursively expands the folder until either there is nothing left,
     * or there will be a folder in which there will be something other than one subfolder.
     */
    public static <T> void normalizeGameDirectory(Context context , T dir) {
        if (dir == null) {
            return;
        }
        if (dir instanceof File it) {
            if (it.listFiles() != null) {
                while (true) {
                    var files = it.listFiles();
                    if (files.length != 1 || !files[0].isDirectory()) break;
                    it = files[0];
                }
                if (it == dir) return;
                Log.i(TAG,"Normalizing game directory: " + it.getAbsolutePath());

                for (var file : it.listFiles()) {
                    var dest = new File(it.getAbsolutePath(), file.getName());
                    Log.d(TAG,"Moving game file"+ file.getAbsolutePath() + " to " + dest.getAbsolutePath());
                    if (file.renameTo(dest)) {
                        Log.i(TAG,"Renaming file success");
                    } else {
                        Log.e(TAG,"Renaming file error");
                    }
                }
            }
        } else if (dir instanceof DocumentFile it) {
            if (it.listFiles() != null) {
                while (true) {
                    var files = it.listFiles();
                    if (files.length != 1 || !files[0].isDirectory()) break;
                    it = files[0];
                }
                if (it == dir) return;

                for (var file : it.listFiles()) {
                    var dest = DocumentFile.fromTreeUri(context , file.getUri());
                    if (file.renameTo(dest.getName())) {
                        Log.i(TAG,"Renaming file success");
                    } else {
                        Log.e(TAG,"Renaming file error");
                    }
                }
            }
        }
    }

    public static boolean isDirContainsGameFile(DocumentFile dir) {
        if (dir == null) {
            return false;
        }

        for (var file : dir.listFiles()) {
            var dirName = file.getName();
            if (dirName == null) return false;
            var lcName = dirName.toLowerCase(Locale.ROOT);
            if (lcName.endsWith(".qsp") || lcName.endsWith(".gam"))
                return true;
        }

        return false;
    }

    @WorkerThread
    public static long calculateDirSize(DocumentFile dir) {
        if (dir.exists()) {
            long result = 0;
            var fileList = dir.listFiles();
            for (var file : fileList) {
                if (file.isDirectory()) {
                    result += calculateDirSize(file);
                } else {
                    result += file.length();
                }
            }
            return result;
        }
        return 0;
    }

}

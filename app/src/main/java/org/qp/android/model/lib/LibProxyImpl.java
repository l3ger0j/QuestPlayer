package org.qp.android.model.lib;

import static org.qp.android.helpers.utils.FileUtil.documentWrap;
import static org.qp.android.helpers.utils.FileUtil.findOrCreateFile;
import static org.qp.android.helpers.utils.FileUtil.fromRelPath;
import static org.qp.android.helpers.utils.FileUtil.fromFullPath;
import static org.qp.android.helpers.utils.FileUtil.getFileContents;
import static org.qp.android.helpers.utils.FileUtil.writeFileContents;
import static org.qp.android.helpers.utils.PathUtil.getFilename;
import static org.qp.android.helpers.utils.PathUtil.normalizeContentPath;
import static org.qp.android.helpers.utils.StringUtil.getStringOrEmpty;
import static org.qp.android.helpers.utils.StringUtil.isNotEmpty;
import static org.qp.android.helpers.utils.ThreadUtil.isSameThread;
import static org.qp.android.helpers.utils.ThreadUtil.throwIfNotMainThread;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.anggrayudi.storage.file.MimeType;

import org.qp.android.QuestopiaApplication;
import org.qp.android.dto.lib.LibActionData;
import org.qp.android.dto.lib.LibErrorData;
import org.qp.android.dto.lib.LibListItem;
import org.qp.android.dto.lib.LibMenuItem;
import org.qp.android.dto.lib.LibObjectData;
import org.qp.android.dto.lib.LibVarValResp;
import org.qp.android.model.service.AudioPlayer;
import org.qp.android.model.service.HtmlProcessor;
import org.qp.android.ui.game.GameInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public class LibProxyImpl implements LibIProxy, LibICallbacks {
    private final String TAG = this.getClass().getSimpleName();

    private final ReentrantLock libLock = new ReentrantLock();
    private final LibGameState gameState = new LibGameState();
    private final LibNativeMethods nativeMethods = new LibNativeMethods(this);

    private Thread libThread;
    private volatile Handler libHandler;
    private volatile boolean libThreadInit;
    private volatile long gameStartTime;
    private volatile long lastMsCountCallTime;
    private GameInterface gameInterface;

    private final Context context;

    private QuestopiaApplication getApplication() {
        return (QuestopiaApplication) context.getApplicationContext();
    }

    private DocumentFile getCurGameDir() {
        return gameState.gameDir;
    }

    private HtmlProcessor getHtmlProcessor() {
        return getApplication().getHtmlProcessor();
    }

    public AudioPlayer getAudioPlayer() {
        return getApplication().getAudioPlayer();
    }

    public LibProxyImpl(Context context) {
        this.context = context;
    }

    private void runOnQspThread(final Runnable runnable) {
        throwIfNotMainThread();
        if (libThread == null) {
            Log.w(TAG ,"Lib thread has not been started!");
            return;
        }
        if (!libThreadInit) {
            Log.w(TAG ,"Lib thread has been started, but not initialized!");
            return;
        }
        var mLibHandler = libHandler;
        if (mLibHandler == null) return;
        mLibHandler.post(() -> {
            libLock.lock();
            try {
                runnable.run();
            } finally {
                libLock.unlock();
            }
        });
    }

    private boolean loadGameWorld() {
        var gameFileUri = gameState.gameFile.getUri();
        var gameFileFullPath = documentWrap(gameState.gameFile).getAbsolutePath(context);
        final var gameData = getFileContents(context , gameFileUri);
        if (gameData == null) return false;
        if (!nativeMethods.QSPLoadGameWorldFromData(gameData, gameData.length, gameFileFullPath)) {
            showLastQspError();
            return false;
        }

        return true;
    }

    private void showLastQspError() {
        var errorData = (LibErrorData) nativeMethods.QSPGetLastErrorData();
        var locName = getStringOrEmpty(errorData.locName());
        var desc = getStringOrEmpty(nativeMethods.QSPGetErrorDesc(errorData.errorNum()));
        final var message = String.format(
                Locale.getDefault(),
                "Location: %s\nAction: %d\nLine: %d\nError number: %d\nDescription: %s",
                locName,
                errorData.index(),
                errorData.line(),
                errorData.errorNum(),
                desc);
        Log.e(TAG,message);
        if (gameInterface != null) {
            gameInterface.showErrorDialog(message);
        }
    }

    /**
     * Loads the interface configuration - using HTML, font and colors - from the library.
     *
     * @return <code>true</code> if the configuration has changed, otherwise <code>false</code>
     */
    private boolean loadInterfaceConfiguration() {
        var config = gameState.interfaceConfig;
        boolean changed = false;

        var htmlResult = (LibVarValResp) nativeMethods.QSPGetVarValues("USEHTML", 0);
        if (htmlResult.isSuccess()) {
            boolean useHtml = htmlResult.intValue() != 0;
            if (config.useHtml != useHtml) {
                config.useHtml = useHtml;
                changed = true;
            }
        }
        var fSizeResult = (LibVarValResp) nativeMethods.QSPGetVarValues("FSIZE", 0);
        if (fSizeResult.isSuccess() && config.fontSize != fSizeResult.intValue()) {
            config.fontSize = fSizeResult.intValue();
            changed = true;
        }
        var bColorResult = (LibVarValResp) nativeMethods.QSPGetVarValues("BCOLOR", 0);
        if (bColorResult.isSuccess() && config.backColor != bColorResult.intValue()) {
            config.backColor = bColorResult.intValue();
            changed = true;
        }
        var fColorResult = (LibVarValResp) nativeMethods.QSPGetVarValues("FCOLOR", 0);
        if (fColorResult.isSuccess() && config.fontColor != fColorResult.intValue()) {
            config.fontColor = fColorResult.intValue();
            changed = true;
        }
        var lColorResult = (LibVarValResp) nativeMethods.QSPGetVarValues("LCOLOR", 0);
        if (lColorResult.isSuccess() && config.linkColor != lColorResult.intValue()) {
            config.linkColor = lColorResult.intValue();
            changed = true;
        }

        return changed;
    }

    @NonNull
    private ArrayList<LibListItem> getActionsList() {
        var actions = new ArrayList<LibListItem>();
        var count = nativeMethods.QSPGetActionsCount();

        for (int i = 0; i < count; ++i) {
            var action = new LibListItem();
            var actionResult = (LibActionData) nativeMethods.QSPGetActionData(i);
            var curGameDir = getCurGameDir();

            if (actionResult.image() == null) {
                action.pathToImage = null;
            } else {
                var tempPath = normalizeContentPath(getFilename(actionResult.image()));
                var fileFromPath = fromRelPath(context, tempPath, curGameDir);
                if (fileFromPath != null) {
                    action.pathToImage = String.valueOf(fileFromPath.getUri());
                } else {
                    action.pathToImage = null;
                }
            }
            action.text = gameState.interfaceConfig.useHtml
                    ? getHtmlProcessor().removeHtmlTags(actionResult.name())
                    : actionResult.name();

            actions.add(action);

        }

        return actions;
    }

    @NonNull
    private ArrayList<LibListItem> getObjectsList() {
        var objects = new ArrayList<LibListItem>();
        var count = nativeMethods.QSPGetObjectsCount();

        for (int i = 0; i < count; i++) {
            var object = new LibListItem();
            var objectResult = (LibObjectData) nativeMethods.QSPGetObjectData(i);
            var curGameDir = getCurGameDir();

            if (objectResult.name().contains("<img")) {
                if (getHtmlProcessor().isContainsHtmlTags(objectResult.name())) {
                    var tempPath = getHtmlProcessor().getSrcDir(objectResult.name());
                    var fileFromPath = fromRelPath(context, tempPath, curGameDir);
                    object.pathToImage = String.valueOf(fileFromPath);
                } else {
                    var fileFromPath = fromRelPath(context, objectResult.name(), curGameDir);
                    object.pathToImage = String.valueOf(fileFromPath);
                }
            } else {
                object.pathToImage = objectResult.image();
                object.text = gameState.interfaceConfig.useHtml
                        ? getHtmlProcessor().removeHtmlTags(objectResult.name())
                        : objectResult.name();
            }
            objects.add(object);
        }

        return objects;
    }

    // region LibQpProxy

    public void startLibThread() {
        libThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    nativeMethods.QSPInit();
                    if (Looper.myLooper() == null) {
                        Looper.prepare();
                    }
                    libHandler = new Handler(Looper.myLooper());
                    libThreadInit = true;
                    Looper.loop();
                    nativeMethods.QSPDeInit();
                } catch (Throwable t) {
                    Log.e(TAG , "lib thread has stopped exceptionally" , t);
                    Thread.currentThread().interrupt();
                }
            }
        } , "libQSP");
        libThread.start();
    }

    public void stopLibThread() {
        throwIfNotMainThread();
        if (libThread == null) return;
        if (libThreadInit) {
            var handler = libHandler;
            if (handler != null) {
                handler.getLooper().quitSafely();
            }
            libThreadInit = false;
        } else {
            Log.w(TAG,"libqsp thread has been started, but not initialized");
        }
        libThread.interrupt();
    }

    public void enableDebugMode (boolean isDebug) {
        runOnQspThread(() -> nativeMethods.QSPEnableDebugMode(isDebug));
    }

    @Override
    public void runGame(final String id,
                        final String title,
                        final DocumentFile dir,
                        final DocumentFile file) {
        runOnQspThread(() -> doRunGame(id, title, dir, file));
    }

    private void doRunGame(final String id,
                           final String title,
                           final DocumentFile dir,
                           final DocumentFile file) {
        gameInterface.doWithCounterDisabled(() -> {
            getAudioPlayer().closeAllFiles();
            gameState.reset();
            gameState.gameRunning = true;
            gameState.gameId = id;
            gameState.gameTitle = title;
            gameState.gameDir = dir;
            gameState.gameFile = file;
            getApplication().setCurrentGameDir(dir);
            if (!loadGameWorld()) return;
            gameStartTime = SystemClock.elapsedRealtime();
            lastMsCountCallTime = 0;
            if (!nativeMethods.QSPRestartGame(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void restartGame() {
        runOnQspThread(() ->
                doRunGame(gameState.gameId, gameState.gameTitle,
                        gameState.gameDir, gameState.gameFile));
    }

    @Override
    public void loadGameState(final Uri uri) {
        if (!isSameThread(libHandler.getLooper().getThread())) {
            runOnQspThread(() -> loadGameState(uri));
            return;
        }
        final var gameData = getFileContents(context , uri);
        if (gameData == null) return;
        if (!nativeMethods.QSPOpenSavedGameFromData(gameData, gameData.length, true)) {
            showLastQspError();
        }
    }

    @Override
    public void saveGameState(final Uri uri) {
        if (!isSameThread(libHandler.getLooper().getThread())) {
            runOnQspThread(() -> saveGameState(uri));
            return;
        }
        final var gameData = nativeMethods.QSPSaveGameAsData(false);
        if (gameData == null) return;
        writeFileContents(context , uri , gameData);
    }

    @Override
    public void onActionClicked(final int index) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPSetSelActionIndex(index, false)) {
                showLastQspError();
            }
            if (!nativeMethods.QSPExecuteSelActionCode(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onObjectSelected(final int index) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPSetSelObjectIndex(index, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onInputAreaClicked() {
        final var inter = gameInterface;
        if (inter == null) return;
        runOnQspThread(() -> {
            var input = inter.showInputDialog("userInputTitle");
            nativeMethods.QSPSetInputStrText(input);
            if (!nativeMethods.QSPExecUserInput(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onUseExecutorString() {
        final var inter = gameInterface;
        if (inter == null) return;
        runOnQspThread(() -> {
            var input = inter.showExecutorDialog("execStringTitle");
            if (!nativeMethods.QSPExecString(input, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void execute(final String code) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPExecString(code, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void executeCounter() {
        if (libLock.isLocked()) return;
        runOnQspThread(() -> {
            if (!nativeMethods.QSPExecCounter(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public LibGameState getGameState() {
        return gameState;
    }

    @Override
    public void setGameInterface(GameInterface view) {
        gameInterface = view;
    }

    // endregion LibQpProxy

    // region LibQpCallbacks

    @Override
    public void RefreshInt() {
        var request = new LibRefIRequest();
        var configChanged = loadInterfaceConfiguration();

        if (configChanged) {
            request.isIConfigChanged = true;
        }
        if (nativeMethods.QSPIsMainDescChanged()) {
            if (gameState.mainDesc != null) {
                if (!gameState.mainDesc.equals(nativeMethods.QSPGetMainDesc())) {
                    gameState.mainDesc = nativeMethods.QSPGetMainDesc();
                    request.isMainDescChanged = true;
                }
            } else {
                gameState.mainDesc = nativeMethods.QSPGetMainDesc();
                request.isMainDescChanged = true;
            }
        }
        if (nativeMethods.QSPIsActionsChanged()) {
            if (gameState.actionsList != null) {
                if (gameState.actionsList != getActionsList()) {
                    gameState.actionsList = getActionsList();
                    request.isActionsChanged = true;
                }
            } else {
                gameState.actionsList = getActionsList();
                request.isActionsChanged = true;
            }
        }
        if (nativeMethods.QSPIsObjectsChanged()) {
            if (gameState.objectsList != null) {
                if (gameState.objectsList != getObjectsList()) {
                    gameState.objectsList = getObjectsList();
                    request.isObjectsChanged = true;
                }
            } else {
                gameState.objectsList = getObjectsList();
                request.isObjectsChanged = true;
            }
        }
        if (nativeMethods.QSPIsVarsDescChanged()) {
            if (gameState.varsDesc != null) {
                if (!gameState.varsDesc.equals(nativeMethods.QSPGetVarsDesc())) {
                    gameState.varsDesc = nativeMethods.QSPGetVarsDesc();
                    request.isVarsDescChanged = true;
                }
            } else {
                gameState.varsDesc = nativeMethods.QSPGetVarsDesc();
                request.isVarsDescChanged = true;
            }
        }

        var inter = gameInterface;
        if (inter != null) {
            inter.refresh(request);
        }
    }

    @Override
    public void ShowPicture(String path) {
        var inter = gameInterface;
        if (inter == null) return;

        if (isNotEmpty(path)) {
            var picFile = fromFullPath(context, path, getCurGameDir());
            if (picFile == null) return;
            inter.showPicture(String.valueOf(picFile.getUri()));
        }
    }

    @Override
    public void SetTimer(int msecs) {
        var inter = gameInterface;
        if (inter != null) {
            inter.setCounterInterval(msecs);
        }
    }

    @Override
    public void ShowMessage(String message) {
        var inter = gameInterface;
        if (inter != null) {
            inter.showMessage(message);
        }
    }

    @Override
    public void PlayFile(String path, int volume) {
        if (isNotEmpty(path)) {
            getAudioPlayer().playFile(path, volume);
        }
    }

    @Override
    public boolean IsPlayingFile(final String path) {
        return isNotEmpty(path) && getAudioPlayer().isPlayingFile(path);
    }

    @Override
    public void CloseFile(String path) {
        if (isNotEmpty(path)) {
            getAudioPlayer().closeFile(path);
        } else {
            getAudioPlayer().closeAllFiles();
        }
    }

    @Override
    public void OpenGame(String filename) {
        var inter = gameInterface;
        if (inter == null) return;

        if (filename == null) {
            inter.showLoadGamePopup();
        } else {
            try {
                var saveFile = fromFullPath(context, filename, getCurGameDir());
                if (saveFile == null) {
                    Log.e(TAG , "Save file not found");
                    return;
                }
                var saveFileUri = saveFile.getUri();
                inter.doWithCounterDisabled(() -> loadGameState(saveFileUri));
            } catch (Exception e) {
                Log.e(TAG , "Error: ", e);
            }
        }
    }

    @Override
    public void SaveGame(String filename) {
        if (filename == null) {
            var inter = gameInterface;
            if (inter == null) return;
            inter.showSaveGamePopup();
        } else {
            var file = new File(filename);
            var saveFile = findOrCreateFile(context, getCurGameDir(), file.getName(), MimeType.TEXT);
            if (saveFile != null) {
                saveGameState(saveFile.getUri());
            } else {
                Log.e(TAG , "Error access dir");
            }
        }
    }

    @Override
    public String InputBox(String prompt) {
        return gameInterface != null ? gameInterface.showInputDialog(prompt) : null;
    }

    @Override
    public int GetMSCount() {
        var now = SystemClock.elapsedRealtime();
        if (lastMsCountCallTime == 0) {
            lastMsCountCallTime = gameStartTime;
        }
        var dt = (int) (now - lastMsCountCallTime);
        lastMsCountCallTime = now;
        return dt;
    }

    @Override
    public void AddMenuItem(String name, String imgPath) {
        var item = new LibMenuItem(name , imgPath);
        gameState.menuItemsList.add(item);
    }

    @Override
    public void ShowMenu() {
        var inter = gameInterface;
        if (inter == null) return;
        int result = inter.showMenu();
        if (result != -1) {
            nativeMethods.QSPSelectMenuItem(result);
        }
    }

    @Override
    public void DeleteMenu() {
        gameState.menuItemsList.clear();
    }

    @Override
    public void Wait(int msecs) {
        try {
            Thread.sleep(msecs);
        } catch (InterruptedException ex) {
            Log.e(TAG,"Wait failed", ex);
        }
    }

    @Override
    public void ShowWindow(int type, boolean isShow) {
        var inter = gameInterface;
        if (inter == null) return;
        var windowType = LibWindowType.values()[type];
        inter.showWindow(windowType, isShow);
    }

    @Override
    public byte[] GetFileContents(String path) {
        var targetFile = fromFullPath(context, path, getCurGameDir());
        if (targetFile == null) return null;
        var targetFileUri = targetFile.getUri();
        return getFileContents(context , targetFileUri);
    }

    @Override
    public void ChangeQuestPath(String path) {
        var newGameDir = fromFullPath(context, path , getCurGameDir());
        if (newGameDir == null || !newGameDir.exists()) {
            Log.e(TAG,"Game directory not found: " + path);
            return;
        }
        if (!Objects.equals(getCurGameDir() , newGameDir)) {
            gameState.gameDir = newGameDir;
            getApplication().setCurrentGameDir(newGameDir);
        }
    }

    // endregion LibQpCallbacks
}

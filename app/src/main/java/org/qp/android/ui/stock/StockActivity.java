package org.qp.android.ui.stock;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static org.qp.android.helpers.utils.FileUtil.documentWrap;
import static org.qp.android.helpers.utils.FileUtil.forceDelFile;
import static org.qp.android.helpers.utils.JsonUtil.jsonToObject;
import static org.qp.android.ui.stock.StockViewModel.CODE_PICK_IMAGE_FILE;
import static org.qp.android.ui.stock.StockViewModel.CODE_PICK_MOD_FILE;
import static org.qp.android.ui.stock.StockViewModel.CODE_PICK_PATH_FILE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anggrayudi.storage.SimpleStorageHelper;
import com.anggrayudi.storage.file.DocumentFileCompat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.javiersantos.appupdater.AppUpdater;
import com.github.javiersantos.appupdater.enums.UpdateFrom;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.qp.android.QuestPlayerApplication;
import org.qp.android.R;
import org.qp.android.data.db.Game;
import org.qp.android.data.db.GameDao;
import org.qp.android.data.db.GameDatabase;
import org.qp.android.databinding.ActivityStockBinding;
import org.qp.android.dto.stock.GameData;
import org.qp.android.helpers.utils.ViewUtil;
import org.qp.android.ui.dialogs.StockDialogType;
import org.qp.android.ui.settings.SettingsActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class StockActivity extends AppCompatActivity {

    private static final int POST_NOTIFICATION = 203;
    private final String TAG = this.getClass().getSimpleName();

    private HashMap<String, GameData> gamesMap = new HashMap<>();
    private StockViewModel stockViewModel;

    private NavController navController;

    @Inject private GameDatabase gameDatabase;
    @Inject private GameDao gameDao;

    private ActionMode actionMode;
    protected ActivityStockBinding activityStockBinding;
    private boolean isEnable = false;
    private ExtendedFloatingActionButton mFAB;
    private RecyclerView mRecyclerView;
    private ArrayList<GameData> tempList;
    private final ArrayList<GameData> selectList = new ArrayList<>();

    private ActivityResultLauncher<Intent> rootFolderLauncher;
    private final SimpleStorageHelper storageHelper = new SimpleStorageHelper(this);

    private File listDirsFile;

    SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener = (sharedPreferences , key) -> {
        if (key == null) return;
        switch (key) {
            case "binPref" -> stockViewModel.refreshGameData();
            case "lang" -> {
                if (sharedPreferences.getString("lang", "ru").equals("ru")) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"));
                } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));
                }
            }
        }
    };

    public File getListDirsFile() {
        return listDirsFile;
    }

    public void setRecyclerView(RecyclerView mRecyclerView) {
        this.mRecyclerView = mRecyclerView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        var splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setKeepOnScreenCondition(() -> false);
        super.onCreate(savedInstanceState);

        PreferenceManager
                .getDefaultSharedPreferences(getApplication())
                .registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Prevent jumping of the player on devices with cutout
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        }

        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        activityStockBinding = ActivityStockBinding.inflate(getLayoutInflater());
        stockViewModel = new ViewModelProvider(this).get(StockViewModel.class);
        activityStockBinding.setStockVM(stockViewModel);
        stockViewModel.activityObserver.setValue(this);
        gamesMap = stockViewModel.getGamesMap();

        mFAB = activityStockBinding.stockFAB;
        stockViewModel.doIsHideFAB.observe(this , aBoolean -> {
            if (aBoolean) {
                mFAB.hide();
            } else {
                mFAB.show();
            }
        });
        mFAB.setOnClickListener(view -> showDirPickerDialog());

        storageHelper.setOnFileSelected((integer , documentFiles) -> {
            var boxDocumentFiles = Optional.ofNullable(documentFiles);
            if (boxDocumentFiles.isEmpty()) {
                showErrorDialog("File is not selected");
            } else {
                var unBoxDocFiles = boxDocumentFiles.get();
                switch (integer) {
                    case CODE_PICK_IMAGE_FILE -> unBoxDocFiles.forEach(documentFile -> {
                        switch (documentWrap(documentFile).getExtension()) {
                            case "png" , "jpg" , "jpeg" -> {
                                getContentResolver().takePersistableUriPermission(documentFile.getUri() ,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                stockViewModel.setTempImageFile(documentFile);
                            }
                        }
                    });
                    case CODE_PICK_PATH_FILE -> unBoxDocFiles.forEach(documentFile -> {
                        switch (documentWrap(documentFile).getExtension()) {
                            case "qsp", "gam" ->
                                    stockViewModel.setTempPathFile(documentFile);
                        }
                    });
                    case CODE_PICK_MOD_FILE -> unBoxDocFiles.forEach(documentFile -> {
                        if ("qsp".equals(documentWrap(documentFile).getExtension())) {
                            stockViewModel.setTempModFile(documentFile);
                        }
                    });
                }
            }
            return null;
        });

        rootFolderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult() , result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                var data = result.getData();
                if (data == null) return;
                var uri = data.getData();
                if (uri == null) return;

                getContentResolver().takePersistableUriPermission(uri ,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                var rootFolder = DocumentFileCompat.fromUri(this , uri);
                var application = (QuestPlayerApplication) getApplication();
                if (application != null) application.setCurrentGameDir(rootFolder);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this , Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this , new String[]{Manifest.permission.POST_NOTIFICATIONS} , POST_NOTIFICATION);
            }
        }

        loadSettings();

        Log.i(TAG , "Stock Activity created");

        setContentView(activityStockBinding.getRoot());

        var navFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.stockFragHost);
        if (navFragment != null) {
            navController = navFragment.getNavController();
        }
        if (savedInstanceState == null) {
            navController.navigate(R.id.stockRecyclerFragment);
        }

        stockViewModel.emitter.observe(this , eventNavigation -> {
            if (eventNavigation instanceof StockFragmentNavigation.ShowErrorDialog errorDialog) {
                switch (errorDialog.getErrorType()) {
                    case FOLDER_ERROR ->
                            showErrorDialog(getString(R.string.gamesFolderError));
                    case EXCEPTION ->
                            showErrorDialog(getString(R.string.error)
                                    + ": " + errorDialog.getErrorMessage());
                }
            } else if (eventNavigation instanceof StockFragmentNavigation.ShowGameFragment gameFragment) {
                onItemClick(gameFragment.getPosition());
            } else if (eventNavigation instanceof StockFragmentNavigation.ShowActionMode) {
                onLongItemClick();
            } else if (eventNavigation instanceof StockFragmentNavigation.ShowFilePicker filePicker) {
                showFilePickerActivity(filePicker.getRequestCode() , filePicker.getMimeTypes());
            }
        });

        new AppUpdater(this)
                .setUpdateFrom(UpdateFrom.GITHUB)
                .setGitHubUserAndRepo("l3ger0j" , "Questopia")
                .start();

        getOnBackPressedDispatcher().addCallback(this , new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setDisplayHomeAsUpEnabled(false);
                }
                if (navController.getCurrentDestination() != null) {
                    if (Objects.equals(navController.getCurrentDestination().getLabel()
                            , "StockRecyclerFragment")) {
                        finish();
                    } else {
                        stockViewModel.doIsHideFAB.setValue(false);
                        navController.popBackStack();
                    }
                } else {
                    finish();
                }
            }
        });
    }

    public void restoreListDirsFromFile() {
        try {
            var ref = new TypeReference<HashMap<String , String>>() {};
            var mapFiles = jsonToObject(listDirsFile , ref);
            var listFile = new ArrayList<DocumentFile>();
            for (var value : mapFiles.values()) {
                var uri = Uri.parse(value);
                var file = DocumentFileCompat.fromUri(this , uri);
                listFile.add(file);
            }
            stockViewModel.setListGamesDir(listFile);
            stockViewModel.refreshGameData();
        } catch (IOException e) {
            Log.e(TAG , "Error: ", e);
        }
    }

    public void dropPersistable(Uri folderUri) {
        try {
            getContentResolver().releasePersistableUriPermission(
                    folderUri ,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode ,
                                    int resultCode ,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode , resultCode , data);
        storageHelper.getStorage().onActivityResult(requestCode , resultCode , data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode ,
                                           @NonNull String[] permissions ,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode , permissions , grantResults);
        if (requestCode == POST_NOTIFICATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ViewUtil.showSnackBar(findViewById(android.R.id.content) , "Success");
            } else {
                ViewUtil.showSnackBar(findViewById(android.R.id.content) , "Permission denied to post notification");
            }
        }
        storageHelper.onRequestPermissionsResult(requestCode , permissions , grantResults);
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
        stockViewModel.doIsHideFAB.setValue(false);
        navController.popBackStack();
        return true;
    }

    private void loadSettings() {
        var cache = getExternalCacheDir();
        listDirsFile = new File(cache , "tempListDir");

        if (listDirsFile.exists()) {
            restoreListDirsFromFile();
        }
    }

    public void showErrorDialog(String errorMessage) {
        stockViewModel.showDialogFragment(getSupportFragmentManager() ,
                StockDialogType.ERROR_DIALOG , errorMessage);
    }

    public void showFilePickerActivity(int requestCode , String[] mimeTypes) {
        storageHelper.openFilePicker(requestCode , false , mimeTypes);
    }

    public void showDirPickerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var intentQ = new Intent();
            var sm = (StorageManager) getSystemService(Activity.STORAGE_SERVICE);
            var sv = sm.getPrimaryStorageVolume();
            intentQ = sv.createOpenDocumentTreeIntent();
            intentQ.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intentQ.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intentQ.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            rootFolderLauncher.launch(intentQ);
        } else {
            var intentLQ = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            var flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION;
            intentLQ.addFlags(flags);
            rootFolderLauncher.launch(intentLQ);
        }
    }

    public void onItemClick(int position) {
        if (isEnable) {
            for (var gameData : gamesMap.values()) {
                if (!gameData.isFileInstalled()) continue;
                tempList.add(gameData);
            }
            var mViewHolder = mRecyclerView.findViewHolderForAdapterPosition(position);
            if (mViewHolder == null) return;
            var adapterPosition = mViewHolder.getAdapterPosition();
            if (adapterPosition == NO_POSITION) return;
            if (adapterPosition < 0 || adapterPosition >= tempList.size()) return;
            var gameData = tempList.get(adapterPosition);
            if (selectList.isEmpty() || !selectList.contains(gameData)) {
                selectList.add(gameData);
                var cardView = (CardView) mViewHolder.itemView.findViewWithTag("gameCardView");
                cardView.setCardBackgroundColor(Color.LTGRAY);
            } else {
                selectList.remove(gameData);
                var cardView = (CardView) mViewHolder.itemView.findViewWithTag("gameCardView");
                cardView.setCardBackgroundColor(Color.DKGRAY);
            }
        } else {
            stockViewModel.getGameDataList().observe(this , gameData -> {
                if (!gameData.isEmpty() && gameData.size() > position) {
                    stockViewModel.setCurrGameData(gameData.get(position));
                }
            });
            navController.navigate(R.id.stockGameFragment);
            stockViewModel.doIsHideFAB.setValue(true);
        }
    }

    public void onLongItemClick() {
        if (isEnable) {
            return;
        }
        var callback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode , Menu menu) {
                mode.getMenuInflater().inflate(R.menu.menu_delete , menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode , Menu menu) {
                tempList = stockViewModel.getSortedGames();
                isEnable = true;
                return true;
            }

            @SuppressLint("NonConstantResourceId")
            @Override
            public boolean onActionItemClicked(ActionMode mode , MenuItem item) {
                int itemId = item.getItemId();
                switch (itemId) {
                    case R.id.delete_game -> {
                        var service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                        for (var data : selectList) {
                            CompletableFuture
                                    .runAsync(() -> tempList.remove(data) , service)
                                    .thenCombineAsync(
                                            stockViewModel.removeDirFromListDirsFile(listDirsFile , data.gameDir.getName()) ,
                                            (unused , unused2) -> null ,
                                            service
                                    )
                                    .thenRunAsync(() -> forceDelFile(getApplication() , data.gameDir) , service)
                                    .thenRunAsync(() -> dropPersistable(data.gameDir.getUri()) , service)
                                    .thenRun(() -> stockViewModel.refreshGameData())
                                    .exceptionally(ex -> {
                                        showErrorDialog(getString(R.string.error) + ": " + ex);
                                        return null;
                                    });
                        }
                        actionMode.finish();
                    }
                    case R.id.select_all -> {
                        if (selectList.size() == tempList.size()) {
                            selectList.clear();
                            for (int childCount = mRecyclerView.getChildCount(), i = 0; i < childCount; ++i) {
                                final var holder =
                                        mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
                                var cardView = (CardView) holder.itemView.findViewWithTag("gameCardView");
                                cardView.setCardBackgroundColor(Color.DKGRAY);
                            }
                        } else {
                            selectList.clear();
                            selectList.addAll(tempList);
                            for (int childCount = mRecyclerView.getChildCount(), i = 0; i < childCount; ++i) {
                                final var holder =
                                        mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
                                var cardView = (CardView) holder.itemView.findViewWithTag("gameCardView");
                                cardView.setCardBackgroundColor(Color.LTGRAY);
                            }
                        }
                    }
                }
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                for (int childCount = mRecyclerView.getChildCount(), i = 0; i < childCount; ++i) {
                    final var holder =
                            mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
                    var cardView = (CardView) holder.itemView.findViewWithTag("gameCardView");
                    cardView.setCardBackgroundColor(Color.DKGRAY);
                }
                actionMode = null;
                isEnable = false;
                tempList.clear();
                selectList.clear();
                mFAB.show();
            }
        };

        mFAB.hide();
        actionMode = startSupportActionMode(callback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stockViewModel.saveListDirsIntoFile(listDirsFile);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        PreferenceManager
                .getDefaultSharedPreferences(getApplication())
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);

        Log.i(TAG , "Stock Activity destroyed");
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }

        stockViewModel.refreshIntGamesDirectory();
        stockViewModel.doIsHideFAB.setValue(false);
        navController.navigate(R.id.stockRecyclerFragment);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        var inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_stock , menu);
        return true;
    }

    @Override
    @SuppressLint("NonConstantResourceId")
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_options -> {
                startActivity(new Intent(this , SettingsActivity.class));
                return true;
            }
            case R.id.action_search ->
                    Optional.ofNullable((SearchView) item.getActionView()).ifPresent(searchView ->
                            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                                @Override
                                public boolean onQueryTextSubmit(String query) {
                                    return false;
                                }

                                @Override
                                public boolean onQueryTextChange(String newText) {
                                    var gameDataList = stockViewModel.getSortedGames();
                                    var filteredList = new ArrayList<GameData>();
                                    gameDataList.forEach(gameData -> {
                                        if (gameData.title.toLowerCase(Locale.getDefault())
                                                .contains(newText.toLowerCase(Locale.getDefault()))) {
                                            filteredList.add(gameData);
                                        }
                                    });
                                    if (!filteredList.isEmpty()) {
                                        stockViewModel.setValueGameDataList(filteredList);
                                    }
                                    return true;
                                }
                            }));
        }
        return false;
    }

}

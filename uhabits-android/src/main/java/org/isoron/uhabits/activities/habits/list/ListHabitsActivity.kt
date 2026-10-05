package org.isoron.uhabits.activities.habits.list

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.bridge.BridgeArchiveStore
import org.isoron.uhabits.bridge.BridgeSnapshotFactory
import org.isoron.uhabits.bridge.BridgeSnapshotStore
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.ThemeSwitcher.Companion.THEME_DARK
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.restartWithFade

class ListHabitsActivity :
    AppCompatActivity(),
    Preferences.Listener {

    var pureBlack: Boolean = false

    lateinit var appComponent:
            HabitsApplicationComponent

    lateinit var component:
            HabitsActivityComponent

    lateinit var taskRunner:
            TaskRunner

    lateinit var adapter:
            HabitCardListAdapter

    lateinit var rootView:
            ListHabitsRootView

    lateinit var screen:
            ListHabitsScreen

    lateinit var prefs:
            Preferences

    lateinit var midnightTimer:
            MidnightTimer

    private var permissionAlreadyRequested = false

    private val permissionLauncher =
        registerForActivityResult(
            RequestPermission()
        ) { isGranted: Boolean ->

            if (isGranted) {
                scheduleReminders()
            } else {
                Log.i(
                    "ListHabitsActivity",
                    "POST_NOTIFICATIONS denied"
                )
            }
        }

    private val bridgeArchiveLauncher =
        registerForActivityResult(
            CreateDocument("text/plain")
        ) { uri ->

            if (uri != null) {
                BridgeArchiveStore.setExportTarget(
                    this,
                    uri
                )

                BridgeArchiveStore.save(
                    this,
                    appComponent.habitList
                )
            }
        }

    private val bridgeExportLauncher =
        registerForActivityResult(
            CreateDocument("text/plain")
        ) { uri ->

            if (uri != null) {
                BridgeSnapshotStore.setExportTarget(
                    this,
                    uri
                )

                BridgeSnapshotStore.save(
                    this,
                    BridgeSnapshotFactory.create(
                        appComponent.habitList
                    )
                )

                if (
                    !BridgeArchiveStore
                        .hasExportTarget(this)
                ) {
                    bridgeArchiveLauncher.launch(
                        "LoopBridgeArchive.jsonl"
                    )
                }
            }
        }

    private lateinit var menu:
            ListHabitsMenu

    override fun onQuestionMarksChanged() {
        invalidateOptionsMenu()

        menu.behavior.onPreferencesChanged()
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        appComponent =
            (applicationContext as HabitsApplication)
                .component

        component =
            HabitsActivityComponent::class.create(
                parent = appComponent,
                activityContext = this
            )

        component.themeSwitcher.apply()

        prefs =
            appComponent.preferences

        prefs.addListener(this)

        pureBlack =
            prefs.isPureBlackEnabled

        midnightTimer =
            appComponent.midnightTimer

        rootView =
            component.listHabitsRootView

        screen =
            component.listHabitsScreen

        adapter =
            component.habitCardListAdapter

        taskRunner =
            appComponent.taskRunner

        menu =
            component.listHabitsMenu

        Thread.setDefaultUncaughtExceptionHandler(
            BaseExceptionHandler(this)
        )

        component
            .listHabitsBehavior
            .onStartup()

        rootView.applyRootViewInsets()

        setContentView(rootView)

        if (
            !BridgeSnapshotStore
                .hasExportTarget(this)
        ) {
            bridgeExportLauncher.launch(
                "LoopBridgeSnapshot.txt"
            )
        } else if (
            !BridgeArchiveStore
                .hasExportTarget(this)
        ) {
            bridgeArchiveLauncher.launch(
                "LoopBridgeArchive.jsonl"
            )
        }
    }

    override fun onPause() {
        midnightTimer.onPause()

        screen.onDetached()

        adapter.cancelRefresh()

        dismissCurrentDialog()

        super.onPause()
    }

    override fun onResume() {
        adapter.refresh()

        screen.onAttached()

        rootView.postInvalidate()

        midnightTimer.onResume()

        if (
            appComponent
                .reminderScheduler
                .hasHabitsWithReminders()
        ) {
            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.TIRAMISU
            ) {
                scheduleReminders()
            } else {
                if (
                    checkSelfPermission(
                        this,
                        POST_NOTIFICATIONS
                    ) == PERMISSION_GRANTED
                ) {
                    scheduleReminders()
                } else {
                    if (
                        !permissionAlreadyRequested
                    ) {
                        Log.i(
                            "ListHabitsActivity",
                            "Requesting permission: " +
                                    "POST_NOTIFICATIONS"
                        )

                        permissionLauncher.launch(
                            POST_NOTIFICATIONS
                        )

                        permissionAlreadyRequested =
                            true
                    }
                }
            }
        }

        taskRunner.run {
            try {
                AutoBackup(
                    this@ListHabitsActivity
                ).run()

                appComponent
                    .widgetUpdater
                    .updateWidgets()
            } catch (e: Exception) {
                Log.e(
                    "ListHabitActivity",
                    "TaskRunner failed",
                    e
                )
            }
        }

        if (
            prefs.theme == THEME_DARK &&
            prefs.isPureBlackEnabled != pureBlack
        ) {
            restartWithFade(
                ListHabitsActivity::class.java
            )
        }

        parseIntents()

        super.onResume()

        BridgeSnapshotStore.save(
            this,
            BridgeSnapshotFactory.create(
                appComponent.habitList
            )
        )

        BridgeArchiveStore.saveIfDue(
            this,
            appComponent.habitList
        )
    }

    private fun scheduleReminders() {
        appComponent
            .reminderScheduler
            .scheduleAll()
    }

    override fun onCreateOptionsMenu(
        m: Menu
    ): Boolean {

        menu.onCreate(
            menuInflater,
            m
        )

        return true
    }

    override fun onOptionsItemSelected(
        item: MenuItem
    ): Boolean {

        invalidateOptionsMenu()

        return menu.onItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        request: Int,
        result: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            request,
            result,
            data
        )

        screen.onResult(
            request,
            result,
            data
        )
    }

    private fun parseIntents() {
        if (intent == null) return

        if (intent.action == ACTION_EDIT) {

            val habitId =
                intent.extras
                    ?.getLong("habit")

            val timestampMillis =
                intent.extras
                    ?.getLong("timestamp")

            if (
                habitId != null &&
                timestampMillis != null
            ) {
                val habit =
                    appComponent
                        .habitList
                        .getById(habitId)!!

                val date =
                    LocalDate.fromUnixTime(
                        timestampMillis
                    )

                component
                    .listHabitsBehavior
                    .onEdit(
                        habit,
                        date,
                        0f,
                        0f
                    )
            }
        }

        intent = null
    }

    override fun onNewIntent(
        intent: Intent?
    ) {
        super.onNewIntent(intent)

        setIntent(intent)
    }

    companion object {
        const val ACTION_EDIT =
            "org.isoron.uhabits.ACTION_EDIT"
    }
}
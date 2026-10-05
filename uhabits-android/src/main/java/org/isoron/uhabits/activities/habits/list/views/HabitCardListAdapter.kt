/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */
package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.activities.habits.list.MAX_CHECKMARK_COUNT
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope
import java.util.LinkedList

/**
 * Same Loop habit adapter, but the visible order is a day flow instead of a
 * flat database order. The core database order is left untouched.
 */
@Inject
@ActivityScope
class HabitCardListAdapter(
    @ActivityContext context: Context,
    private val cache: HabitCardListCache,
    private val preferences: Preferences,
    private val midnightTimer: MidnightTimer
) : RecyclerView.Adapter<HabitCardViewHolder?>(),
    HabitCardListCache.Listener,
    MidnightTimer.MidnightListener,
    ListHabitsMenuBehavior.Adapter,
    ListHabitsSelectionMenuBehavior.Adapter {

    val observable: ModelObservable = ModelObservable()
    private var listView: HabitCardListView? = null
    val selected: LinkedList<Habit> = LinkedList()
    private val flowOrganizer = HabitFlowOrganizer(context)

    override fun atMidnight() {
        cache.refreshAllHabits()
    }

    fun cancelRefresh() {
        cache.cancelTasks()
    }

    fun hasNoHabit(): Boolean = cache.hasNoHabit()

    override fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun getSelected(): List<Habit> = ArrayList(selected)

    private fun flowItems(): List<HabitFlowOrganizer.Item> {
        val habits = (0 until cache.habitCount).mapNotNull { position ->
            cache.getHabitByPosition(position)
        }
        return flowOrganizer.organize(habits)
    }

    /** Adapter position now means visual day-flow position. */
    @Deprecated("")
    fun getItem(position: Int): Habit? =
        flowItems().getOrNull(position)?.habit

    override fun getItemCount(): Int = flowItems().size

    override fun getItemId(position: Int): Long =
        getItem(position)?.id ?: RecyclerView.NO_ID

    val isSelectionEmpty: Boolean
        get() = selected.isEmpty()

    /**
     * Manual drag is disabled in day-flow mode, because visual order is derived
     * from routine/time metadata rather than the raw Loop position.
     */
    val isSortable: Boolean
        get() = false

    fun onAttached() {
        cache.onAttached()
        midnightTimer.addListener(this)
    }

    override fun onBindViewHolder(
        holder: HabitCardViewHolder,
        position: Int
    ) {
        val targetList = listView ?: return
        val flow = flowItems()
        val item = flow.getOrNull(position) ?: return
        val habit = item.habit
        val habitId = habit.id ?: return
        val score = cache.getScore(habitId)
        val checkmarks = cache.getCheckmarks(habitId)
        val notes = cache.getNotes(habitId)
        val selectedNow = selected.contains(habit)
        val previousSection = flow.getOrNull(position - 1)?.section

        (holder.itemView as HabitCardView).apply {
            sectionTitle = if (previousSection != item.section) item.section else null
            planningMeta = item.meta
        }

        targetList.bindCardView(
            holder,
            habit,
            score,
            checkmarks,
            notes,
            selectedNow
        )
    }

    override fun onViewAttachedToWindow(holder: HabitCardViewHolder) {
        listView?.attachCardView(holder)
    }

    override fun onViewDetachedFromWindow(holder: HabitCardViewHolder) {
        listView?.detachCardView(holder)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): HabitCardViewHolder {
        val view = listView!!.createHabitCardView()
        return HabitCardViewHolder(view)
    }

    fun onDetached() {
        cache.onDetached()
        midnightTimer.removeListener(this)
    }

    /**
     * A life-model change can move an item to another section, so a full small
     * list refresh is safer than forwarding raw cache positions.
     */
    override fun onItemChanged(position: Int) {
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun onItemInserted(position: Int) {
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun onItemMoved(oldPosition: Int, newPosition: Int) {
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun onItemRemoved(position: Int) {
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun onRefreshFinished() {
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun performRemove(selected: List<Habit>) {
        for (habit in selected) cache.remove(habit.id!!)
    }

    /** Kept for interface compatibility; day-flow mode does not call this. */
    fun performReorder(from: Int, to: Int) {
        val fromHabit = getItem(from) ?: return
        val toHabit = getItem(to) ?: return
        cache.reorder(fromHabit.position, toHabit.position)
    }

    override fun refresh() {
        cache.refreshAllHabits()
    }

    override fun setFilter(matcher: HabitMatcher) {
        cache.setFilter(matcher)
    }

    fun setListView(listView: HabitCardListView?) {
        this.listView = listView
    }

    override var primaryOrder: HabitList.Order
        get() = cache.primaryOrder
        set(value) {
            cache.primaryOrder = value
            preferences.defaultPrimaryOrder = value
        }

    override var secondaryOrder: HabitList.Order
        get() = cache.secondaryOrder
        set(value) {
            cache.secondaryOrder = value
            preferences.defaultSecondaryOrder = value
        }

    fun toggleSelection(position: Int) {
        val h = getItem(position) ?: return
        val k = selected.indexOf(h)
        if (k < 0) selected.add(h) else selected.remove(h)
        notifyDataSetChanged()
    }

    init {
        cache.setListener(this)
        cache.setCheckmarkCount(MAX_CHECKMARK_COUNT)
        cache.secondaryOrder = preferences.defaultSecondaryOrder
        cache.primaryOrder = preferences.defaultPrimaryOrder
        setHasStableIds(true)
    }
}

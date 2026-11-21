package com.folio.files

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * Swipe-right-to-delete gesture for the file lists. The actual delete is deferred by the caller
 * (see MainActivity.enqueueDeferredDelete) so an undo window stays open while rows keep leaving
 * the list without interruption.
 */
class SwipeToDeleteCallback(
    context: Context,
    private val adapter: FileAdapter,
    private val canSwipe: (FileItem) -> Boolean,
    private val onSwiped: (FileItem) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val density = context.resources.displayMetrics.density
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.swipe_delete_background)
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)!!.mutate().apply {
        setTint(ContextCompat.getColor(context, android.R.color.white))
    }
    private val iconSize = (24 * density).toInt()
    private val iconMargin = (20 * density).toInt()

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val item = itemAt(viewHolder)
        return if (item != null && canSwipe(item)) {
            makeMovementFlags(0, ItemTouchHelper.RIGHT)
        } else {
            0
        }
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val item = itemAt(viewHolder) ?: return
        onSwiped(item)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val itemView = viewHolder.itemView
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0 && itemView.width > 0) {
            val alpha = min(1f, dX / itemView.width)
            backgroundPaint.alpha = (255 * alpha).toInt()
            c.drawRect(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                itemView.left + dX,
                itemView.bottom.toFloat(),
                backgroundPaint,
            )
            icon.alpha = (255 * alpha).toInt()
            val top = itemView.top + (itemView.height - iconSize) / 2
            icon.setBounds(itemView.left + iconMargin, top, itemView.left + iconMargin + iconSize, top + iconSize)
            icon.draw(c)
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun itemAt(viewHolder: RecyclerView.ViewHolder): FileItem? {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return null
        return adapter.currentList.getOrNull(position)
    }
}
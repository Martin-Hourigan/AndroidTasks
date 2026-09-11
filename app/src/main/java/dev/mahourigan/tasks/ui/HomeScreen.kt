package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.TaskFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One card on the grid: a saved filter plus how many tasks it currently matches. */
private data class HomeCard(val filter: TaskFilter, val count: Int)

/**
 * The whole navigation model: a grid of saved filters with live counts.
 *
 * There is no separate "lists" screen because there are no lists — a saved
 * filter *is* a list, so the home screen and the filter list are the same thing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    onOpenFilter: (TaskFilter) -> Unit,
    onEditFilter: (TaskFilter) -> Unit,
    onNewFilter: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Counting means running every filter over every task, so it is done once
    // per data change rather than on every recomposition. Without this, an
    // unrelated recompose re-evaluates every card's predicate against the whole
    // task list.
    val cards = remember(state.snapshot, state.today) {
        state.snapshot.filters
            .sortedBy { it.position }
            .map { filter -> HomeCard(filter, state.count(filter)) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp + contentPadding.calculateTopPadding(),
            bottom = 96.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { card -> card.filter.id }) { card ->
            FilterCard(
                filter = card.filter,
                count = card.count,
                onClick = { onOpenFilter(card.filter) },
                onLongClick = { onEditFilter(card.filter) },
            )
        }
        item(key = "new_filter") { NewFilterCard(onClick = onNewFilter) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterCard(
    filter: TaskFilter,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            // Long press to edit keeps the common action — opening the list — a
            // single tap, without hiding editing behind a separate screen.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            // An empty card is still worth showing — "nothing urgent" is useful
            // information — but it shouldn't compete with one that needs you.
            containerColor = if (count == 0) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = filter.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (count == 0) "Clear" else count.toString(),
                style = if (count == 0) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                    alpha = if (count == 0) 0.6f else 1f,
                ),
            )
        }
    }
}

@Composable
private fun NewFilterCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "+ New card",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

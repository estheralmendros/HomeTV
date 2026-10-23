package com.hometv.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

fun Modifier.focusRequesterOf(requester: FocusRequester): Modifier = focusRequester(requester)

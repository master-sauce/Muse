package com.Music

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Player  : Screen("player")
}
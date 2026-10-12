package dev.docuconf.kotlin.core

/**
 * A value that a reload can replace after load: a file input declared `reload: watch` (SPEC §4.6.2).
 * Read [current] at each use rather than keeping what it returned once. `docuconf-hoplite`'s
 * `Watched` implements it, and so does a watched file input's value in the contract-first mode.
 */
public interface Reloadable<out T : Any> {
    /** The current value. */
    public fun current(): T
}

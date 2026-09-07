package com.sc.mf919pro.kotlin.helper_common

import enums.EnumLogFileName

/**
 * Retained as an alias only. This was a second, hand-maintained copy of [EnumLogFileName]
 * with the same three constants and the same retention counts; each file's comment claimed
 * to be the source of truth and told you to keep the other in step. AsyncLogWriter reads
 * maximumFile from EnumLogFileName, which now lives once in :core, so that is the definition.
 *
 * The alias keeps existing call sites compiling. New code should use [EnumLogFileName].
 */
@Deprecated(
	"Duplicate of enums.EnumLogFileName, which is now the single definition in :core.",
	ReplaceWith("EnumLogFileName", "enums.EnumLogFileName")
)
typealias HelperLogFileName = EnumLogFileName

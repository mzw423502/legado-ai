/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai
import org.junit.Test
class MemoryV2RegressionTest {
    @Test fun boundedMemoryAndLosslessLegacyMigration() = MemoryV2Checks.runAll()
}

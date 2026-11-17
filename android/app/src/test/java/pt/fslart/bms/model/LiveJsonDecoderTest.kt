package pt.fslart.bms.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Linhas no formato do `LiveDebug_SendJson()` (LIVE_DEBUG_JSON.md): os estados
 * vem como TEXTO. Era aqui que a app falhava -- lia-os como numero, ficava
 * sempre em WAIT_HV e o balao nunca aparecia sozinho.
 */
class LiveJsonDecoderTest {

    private fun firmwareLine(
        chargerState: String = "\"CHARGING\"",
        bmsState: String = "\"CHARGING\"",
        prechargeState: String = "\"HV_ON\"",
        handcartSwitch: Int = 1,
        statusOk: Int = 1,
        packCurrentMa: Int = -5900,
        handcartCurrentMa: Int = -5900,
        isaAgeMs: Long = 20
    ): String {
        val cells = (0 until 12).joinToString(",", "[", "]") {
            (0 until 12).joinToString(",", "[", "]") { "4000" }
        }
        val ntcs = (0 until 12).joinToString(",", "[", "]") {
            (0 until 6).joinToString(",", "[", "]") { "250" }
        }
        val zeros = (0 until 12).joinToString(",", "[", "]") { "0" }
        return """{"uptime_ms":123456,""" +
            """"bms":{"state":$bmsState,"prev_state":"IDLE","runtime_s":120,"wwdg_reset":0,"active_faults":0},""" +
            """"ams_error":{"state":"","active":0,"permanent":0,"fault_count":0,"fault_mask_lo":0,"fault_mask_hi":0,"faults":[]},""" +
            """"precharge":{"state":$prechargeState,"vcu_request":0,"sdc_closed":1,"air_pos":1,"air_neg":1,"precharge_relay":0,"discharge":0},""" +
            """"isa_pack":{"current_mA":$packCurrentMa,"voltage_mV":580000,"power_W":-3420,"charge_As":0,"temp_dC":250,"rx_age_ms":$isaAgeMs},""" +
            """"isa_handcart":{"current_mA":$handcartCurrentMa,"voltage_mV":581000,"power_W":3420,"charge_As":0,"temp_dC":250,"rx_age_ms":$isaAgeMs},""" +
            """"soc":{"percent_x100":5550,"used_charge_As":100,"ready":1},""" +
            """"charger":{"state":$chargerState,"handcart_switch":$handcartSwitch,"handcart_age_ms":10,"status_ok":$statusOk,"requested_current_raw":60},""" +
            """"pack":{"slaves_found":12,"adbms_state":"END","any_pec_error":0,"cell_max_mV":4012,"cell_min_mV":3950,"temp_max_cC":3120,"temp_min_cC":2500,"voltage_sum_mV":580400},""" +
            """"balancing":{"active":0,"stage":"","phase":"","mask":$zeros},""" +
            """"board":{"fan_pwm":128,"mcu_temp_dC":350},""" +
            """"json":{"sent":7,"dropped":0,"last_len":3100},""" +
            """"cell_mV":$cells,"ntc_dC":$ntcs,"pec_flags":$zeros,"ow_cell_mask":$zeros,"ow_ntc_mask":$zeros}"""
    }

    private fun decode(line: String): BmsState {
        val state = LiveJsonDecoder(CarProfile.TEK26E).decodeLine(line)
        assertNotNull("a linha devia ser descodificada", state)
        return state!!
    }

    @Test
    fun firmwareTextStates_detectCharging() {
        val state = decode(firmwareLine())
        assertTrue(state.isCharging)
        assertEquals("CHARGING", state.safety.masterState)
        assertEquals("HV_ON", state.safety.prechargeState)
        assertTrue(state.safety.prechargeDone)
        assertEquals(580.4f, state.pack.voltage, 0.01f)
        assertEquals(55.5f, state.pack.soc, 0.01f)
        assertEquals(5.9f, state.charger.outputCurrent, 0.01f)
    }

    @Test
    fun numericStates_stillSupported() {
        val state = decode(firmwareLine(chargerState = "2", bmsState = "1", prechargeState = "16"))
        assertTrue(state.isCharging)
        assertEquals("CHARGING", state.safety.masterState)
        assertTrue(state.safety.prechargeDone)
    }

    @Test
    fun idleOnHandcart_withoutCurrent_isNotCharging() {
        val state = decode(
            firmwareLine(
                chargerState = "\"WAIT_HV\"",
                bmsState = "\"IDLE\"",
                packCurrentMa = 0,
                handcartCurrentMa = 0
            )
        )
        assertFalse(state.isCharging)
        assertEquals("IDLE", state.safety.masterState)
    }

    @Test
    fun currentFlowingFromHandcart_countsAsCharging() {
        // Maquina do carregador ainda a arrancar, mas a corrente ja esta a entrar.
        val state = decode(firmwareLine(chargerState = "\"PRESTART_STOP\""))
        assertTrue(state.isCharging)
    }

    @Test
    fun regenOnTheCar_isNotCharging() {
        // No carro (sem handcart) corrente negativa e regeneracao, nao carga.
        val state = decode(
            firmwareLine(
                chargerState = "\"WAIT_HV\"",
                bmsState = "\"ONMISSION\"",
                handcartSwitch = 0,
                statusOk = 0,
                packCurrentMa = -40000,
                handcartCurrentMa = 0
            )
        )
        assertFalse(state.isCharging)
        assertEquals("car", state.mode)
    }

    @Test
    fun silentIsa_valuesIgnored() {
        val state = decode(firmwareLine(chargerState = "\"DONE\"", isaAgeMs = 5_000))
        assertFalse(state.isCharging)
        assertEquals(0f, state.pack.current, 0.001f)
        assertEquals(0f, state.charger.outputCurrent, 0.001f)
        // A tensao vem da soma das celulas, nao da ISA.
        assertEquals(580.4f, state.pack.voltage, 0.01f)
    }
}

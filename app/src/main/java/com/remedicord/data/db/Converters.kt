package com.remedicord.data.db

import androidx.room.TypeConverter
import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Room type converters. Enums are stored by name by Room itself.
 * Dose amounts use BigDecimal (stored as text) so 0.5 tablet or 2.5 mL are exact, never 0.49999.
 */
class Converters {
    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? = value?.let { json.encodeToString(stringList, it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? = value?.let { json.decodeFromString(stringList, it) }

    private companion object {
        val json = Json
        val stringList = ListSerializer(String.serializer())
    }
}

/** kotlinx.serialization support for BigDecimal (as an exact string) for audit/backup JSON. */
object BigDecimalAsStringSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.remedicord.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

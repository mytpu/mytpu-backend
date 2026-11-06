package org.mytpu.assistant.normalize;

/**
 * Аудитория и корпус ТПУ.
 *
 * @param building номер корпуса (может быть null)
 * @param room     номер аудитории (буквы в нижнем регистре кириллицей)
 */
public record Room(String building, String room) {}

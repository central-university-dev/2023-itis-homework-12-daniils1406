package ru.shop.backend.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.shop.backend.search.config.consts.TypeOfQuery;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class  TypeHelpText {
    private TypeOfQuery type;
    private String text;
}

package backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Champ extrait du XML HELP.TEXT d'une table T24.
 * L'export ne contient que le nom et la description ; type/mandatory/length/defaultValue/validation
 * restent null tant qu'aucune source ne fournit ces métadonnées.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FieldInfo {

    private String name;
    private String type;
    private String description;
    private Boolean mandatory;
    private Integer length;
    private String defaultValue;
    private String validation;
}

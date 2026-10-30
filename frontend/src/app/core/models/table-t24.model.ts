/** TableT24DTO (backend/dto/TableT24DTO.java) : une table T24 du catalogue, avec son XML HELP.TEXT brut. */
export interface TableT24Dto {
  id: number;
  nomTable: string;
  data: string;
  createdAt: string;
  updatedAt: string;
  moduleId: number;
}

/** FieldInfo (backend/dto/FieldInfo.java) : un champ extrait du XML HELP.TEXT d'une table T24. */
export interface FieldInfo {
  name: string;
  type: string | null;
  description: string | null;
  mandatory: boolean | null;
  length: number | null;
  defaultValue: string | null;
  validation: string | null;
}

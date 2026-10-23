// Generated from the admin API's @Serializable classes by ./gradlew :admin:basekt-admin-api:generateTypeScript.
// Do not edit: CI fails if this file doesn't match the classes.
//
// The contract is additive only within a version: properties and enum values may be added, and clients
// must ignore ones they don't know. Removing, renaming or retyping anything needs a new version.

/** The AdminSchema contract version these types describe. */
export const ADMIN_SCHEMA_VERSION = 1;

/** Any JSON value. */
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

/** A JSON object, such as a record keyed by field name. */
export type JsonObject = { [key: string]: JsonValue };

export interface AdminSchema {
  version: number;
  caller: AdminCaller | null;
  resources: AdminResource[];
}

export interface AdminCaller {
  id: string;
  roles: string[];
}

export interface AdminResource {
  name: string;
  fields: AdminField[];
  relations: AdminRelation[];
  operations: Operation[];
  searchable: string[];
  sortable: string[];
}

export interface AdminField {
  name: string;
  type: FieldType;
  required: boolean;
  values: string[] | null;
  target: string | null;
  rules: Rule[];
  bucket: string | null;
  scopedValues: string[] | null;
  auto: Auto | null;
}

export type FieldType = "string" | "int" | "long" | "decimal" | "bool" | "instant" | "enum" | "reference" | "file";

export type Rule = RuleCustom | RuleLength | RuleMax | RuleMin | RulePattern;

export interface RuleCustom {
  kind: "custom";
  name: string;
}

export interface RuleLength {
  kind: "length";
  min: number | null;
  max: number | null;
}

export interface RuleMax {
  kind: "max";
  value: string;
}

export interface RuleMin {
  kind: "min";
  value: string;
}

export interface RulePattern {
  kind: "pattern";
  regex: string;
  message: string | null;
}

export type Auto = AutoCreatedAt | AutoCreatedBy | AutoSequence | AutoUpdatedAt | AutoUpdatedBy | AutoVersion;

export interface AutoCreatedAt {
  kind: "createdAt";
}

export interface AutoCreatedBy {
  kind: "createdBy";
}

export interface AutoSequence {
  kind: "sequence";
  prefix: string;
  digits: number;
  start: number;
}

export interface AutoUpdatedAt {
  kind: "updatedAt";
}

export interface AutoUpdatedBy {
  kind: "updatedBy";
}

export interface AutoVersion {
  kind: "version";
}

export interface AdminRelation {
  name: string;
  kind: RelationKind;
  target: string;
  field: string;
}

export type RelationKind = "belongsTo" | "hasMany";

export type Operation = "read" | "create" | "update" | "delete";

export interface AdminPage {
  items: JsonObject[];
  nextCursor: string | null;
  hasNext: boolean;
  total: number | null;
}

export interface BulkDeleteRequest {
  ids: string[];
}

export interface BulkDeleteResult {
  deleted: string[];
  failed: BulkFailure[];
}

export interface BulkFailure {
  id: string;
  error: ApiError;
}

export interface ApiError {
  error: ErrorCode;
  message: string;
  fields: FieldError[];
}

export type ErrorCode = "bad_request" | "invalid_query" | "unauthorized" | "forbidden" | "not_found" | "conflict" | "payload_too_large" | "unsupported_type" | "validation_failed" | "rejected";

export interface FieldError {
  field: string;
  code: FieldErrorCode;
  message: string;
}

export type FieldErrorCode = "required" | "type" | "min" | "max" | "length" | "pattern" | "enum" | "custom" | "unknown_field" | "read_only" | "missing_reference" | "rejected";

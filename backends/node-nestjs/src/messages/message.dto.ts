import { Transform } from "class-transformer";
import { ContractField } from "../validation.pipe.js";

const KEY_PATTERN = /^[a-z0-9-]+(\.[a-z0-9-]+)*$/;
const LANGUAGE_PATTERN = /^[a-z]{2}$/;
const trimString = ({ value }: { value: unknown }): unknown => (typeof value === "string" ? value.trim() : value);

export class MessageBodyDto {
  @Transform(trimString)
  @ContractField((value) =>
    typeof value === "string" && KEY_PATTERN.test(value) && value.length <= 150
      ? undefined
      : "validation.message.key.invalid",
  )
  key!: string;

  @Transform(trimString)
  @ContractField((value) =>
    typeof value === "string" && LANGUAGE_PATTERN.test(value) ? undefined : "validation.message.language.invalid",
  )
  language!: string;

  @ContractField((value) => {
    if (typeof value !== "string" || value === "") return "validation.message.text.required";
    return value.length > 2000 ? "validation.message.text.too-long" : undefined;
  })
  text!: string;

  @ContractField((value) =>
    value === null || value === undefined || (typeof value === "string" && value.length <= 1000)
      ? undefined
      : "validation.message.description.too-long",
  )
  description: string | null = null;
}

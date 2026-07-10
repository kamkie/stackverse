import { Transform } from "class-transformer";
import { ContractField } from "../validation.pipe.js";

export class UserStatusBodyDto {
  @ContractField((value) => (value === "active" || value === "blocked" ? undefined : "validation.user-status.invalid"))
  status!: "active" | "blocked";

  @Transform(({ value }: { value: unknown }) => (typeof value === "string" ? value.trim() : value))
  @ContractField((value, object) => {
    const status = (object as UserStatusBodyDto).status;
    if (status === "blocked" && (typeof value !== "string" || value === "")) {
      return "validation.block.reason.required";
    }
    return value === null || value === undefined || (typeof value === "string" && value.length <= 1000)
      ? undefined
      : "validation.block.reason.too-long";
  })
  reason?: string;
}

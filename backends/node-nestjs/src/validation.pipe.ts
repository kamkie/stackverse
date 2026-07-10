import { ValidationPipe } from "@nestjs/common";
import {
  Validate,
  type ValidationArguments,
  type ValidationError,
  ValidatorConstraint,
  type ValidatorConstraintInterface,
} from "class-validator";
import { ValidationProblem, type FieldViolation } from "./problems.js";

export type ContractRule = (value: unknown, object: object) => string | undefined;

@ValidatorConstraint({ name: "contractField", async: false })
class ContractFieldConstraint implements ValidatorConstraintInterface {
  validate(value: unknown, arguments_: ValidationArguments): boolean {
    return this.rule(arguments_)(value, arguments_.object) === undefined;
  }

  defaultMessage(arguments_: ValidationArguments): string {
    return this.rule(arguments_)(arguments_.value, arguments_.object) ?? "validation.invalid";
  }

  private rule(arguments_: ValidationArguments): ContractRule {
    return arguments_.constraints[0] as ContractRule;
  }
}

/** A class-validator decorator whose message is the canonical Stackverse key. */
export const ContractField = (rule: ContractRule): PropertyDecorator => Validate(ContractFieldConstraint, [rule]);

function violations(errors: ValidationError[]): FieldViolation[] {
  const result: FieldViolation[] = [];

  const visit = (error: ValidationError, prefix = ""): void => {
    const field = prefix === "" ? error.property : `${prefix}.${error.property}`;
    for (const messageKey of Object.values(error.constraints ?? {})) {
      if (!result.some((item) => item.field === field && item.messageKey === messageKey)) {
        result.push({ field, messageKey });
      }
    }
    for (const child of error.children ?? []) visit(child, field);
  };

  for (const error of errors) visit(error);
  return result;
}

export function contractValidationPipe(): ValidationPipe {
  return new ValidationPipe({
    transform: true,
    whitelist: true,
    forbidNonWhitelisted: false,
    transformOptions: { enableImplicitConversion: false, exposeDefaultValues: true },
    exceptionFactory: (errors) => new ValidationProblem(violations(errors)),
  });
}

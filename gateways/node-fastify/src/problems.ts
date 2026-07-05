import type { FastifyReply } from "fastify";

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
}

export function problem(status: number, title: string, detail: string): Problem {
  return {
    type: "about:blank",
    title,
    status,
    detail,
  };
}

export function sendProblem(reply: FastifyReply, status: number, title: string, detail: string): FastifyReply {
  return reply.code(status).type("application/problem+json").send(problem(status, title, detail));
}

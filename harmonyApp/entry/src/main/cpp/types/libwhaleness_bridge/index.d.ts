export class Engine {
  constructor();
  close(): void;
  dispatch(command: string): string;
}

export function renderContent(command: string): string;

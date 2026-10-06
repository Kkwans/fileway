type ToastImportance = "minor" | "normal" | "important";
type ToastFeedbackOptions = {
  importance?: ToastImportance;
  timeout?: number;
  persistent?: boolean;
};
type IToastSuccess = (message: string, options?: ToastFeedbackOptions) => void;
type IToastError = (
  error: Error | string,
  displayReport?: boolean,
  options?: ToastFeedbackOptions
) => void;
type IToastAction = (
  message: string,
  actionLabel: string,
  action: () => void | Promise<void>,
  options?: ToastFeedbackOptions | number
) => void;

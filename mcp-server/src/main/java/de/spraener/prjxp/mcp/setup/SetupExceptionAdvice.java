package de.spraener.prjxp.mcp.setup;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses = SetupController.class)
public class SetupExceptionAdvice {
    @ExceptionHandler(SetupException.class)
    public ResponseEntity<SetupError> setup(SetupException error) {
        return ResponseEntity.status(error.status()).body(new SetupError(error.code(), error.getMessage(), error.step()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<SetupError> invalid() {
        return ResponseEntity.badRequest().body(new SetupError("INVALID_REQUEST", "Invalid setup request", null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<SetupError> unexpected(RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Internal setup failure ({})", error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new SetupError("INTERNAL_ERROR", "Internal setup failure", null));
    }
}

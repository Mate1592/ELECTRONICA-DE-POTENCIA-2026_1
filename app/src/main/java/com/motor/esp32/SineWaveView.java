package com.motor.esp32;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

/**
 * Vista personalizada que dibuja la onda senoidal AC
 * y muestra la zona de conducción del TRIAC según el ángulo de disparo.
 *
 * Cálculo: ángulo = (tiempoDisparo / semiperiodo) * 180°
 * Para 60 Hz → semiperiodo = 8333 µs
 * Para 50 Hz → semiperiodo = 10000 µs
 */
public class SineWaveView extends View {

    private float firingAngleDeg = 160f; // ángulo de disparo en grados

    private final Paint bgPaint    = new Paint();
    private final Paint axisPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint anglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SineWaveView(Context ctx) { super(ctx); init(); }
    public SineWaveView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public SineWaveView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        bgPaint.setColor(Color.parseColor("#0D1117"));

        axisPaint.setColor(Color.parseColor("#30475E"));
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setStyle(Paint.Style.STROKE);

        gridPaint.setColor(Color.parseColor("#1C2430"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{4, 8}, 0));

        wavePaint.setColor(Color.parseColor("#3D4C5C"));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(2f);

        fillPaint.setColor(Color.parseColor("#152A1E")); // verde oscuro - área conducida
        fillPaint.setStyle(Paint.Style.FILL);

        greenPaint.setColor(Color.parseColor("#3FB950")); // trazo verde conducido
        greenPaint.setStyle(Paint.Style.STROKE);
        greenPaint.setStrokeWidth(3f);

        dashPaint.setColor(Color.parseColor("#FFA657")); // línea de disparo
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(2f);
        dashPaint.setPathEffect(new DashPathEffect(new float[]{10, 6}, 0));

        labelPaint.setColor(Color.parseColor("#6E7681"));
        labelPaint.setTextSize(22f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        anglePaint.setColor(Color.parseColor("#FFA657"));
        anglePaint.setTextSize(27f);
        anglePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** Actualiza el ángulo de disparo y redibuja la vista. */
    public void setFiringAngle(float degrees) {
        this.firingAngleDeg = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth(), h = getHeight();
        final int PL = 38, PR = 12, PT = 24, PB = 30;
        final int dw = w - PL - PR;
        final int dh = h - PT - PB;
        final int cy = PT + dh / 2;
        final float amp = dh * 0.41f;

        // Fondo
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Grilla vertical sutil en 90° y 270°
        float[] gridDeg = {90, 270};
        for (float gd : gridDeg) {
            float gx = PL + (gd / 360f) * dw;
            canvas.drawLine(gx, PT, gx, h - PB, gridPaint);
        }

        // Eje horizontal (cero)
        canvas.drawLine(PL, cy, w - PR, cy, axisPaint);

        // Eje vertical en 180° (separador de semiciclos)
        float xMid = PL + dw / 2f;
        canvas.drawLine(xMid, PT, xMid, h - PB, axisPaint);

        // ── Dibujar onda completa (gris oscuro) ──
        Path wave = new Path();
        for (int i = 0; i <= dw; i++) {
            float rad = (float) (i * 2.0 * Math.PI / dw);
            float y = cy - amp * (float) Math.sin(rad);
            if (i == 0) wave.moveTo(PL + i, y);
            else wave.lineTo(PL + i, y);
        }
        canvas.drawPath(wave, wavePaint);

        // ── Zonas conducidas ──
        // Semiciclo positivo: de firingAngleDeg → 180°
        drawConductedHalf(canvas, PL, dw, cy, amp, firingAngleDeg, 180f);
        // Semiciclo negativo: de (180 + firingAngleDeg) → 360°
        drawConductedHalf(canvas, PL, dw, cy, amp, 180f + firingAngleDeg, 360f);

        // ── Líneas de ángulo de disparo (naranja punteado) ──
        float xFire1 = PL + (firingAngleDeg / 360f) * dw;
        float xFire2 = PL + ((180f + firingAngleDeg) / 360f) * dw;
        canvas.drawLine(xFire1, PT, xFire1, h - PB, dashPaint);
        canvas.drawLine(xFire2, PT, xFire2, h - PB, dashPaint);

        // ── Etiqueta del ángulo ──
        float labelX = Math.min(xFire1 + 6, w - PR - 130);
        canvas.drawText(String.format("α=%.1f°", firingAngleDeg), labelX, PT + 22, anglePaint);

        // ── Marcas y etiquetas de grados en el eje X ──
        int[] markers = {0, 90, 180, 270, 360};
        for (int deg : markers) {
            float mx = PL + (deg / 360f) * dw;
            canvas.drawLine(mx, cy - 7, mx, cy + 7, axisPaint);
            canvas.drawText(deg + "°", mx, h - 4, labelPaint);
        }
    }

    /**
     * Dibuja el área conducida entre startDeg y endDeg.
     * Relleno oscuro verde + trazo verde brillante encima.
     */
    private void drawConductedHalf(Canvas canvas, int PL, int dw, int cy, float amp,
                                   float startDeg, float endDeg) {
        int iStart = (int) ((startDeg / 360f) * dw);
        int iEnd   = Math.min((int) ((endDeg / 360f) * dw), dw);
        if (iStart >= iEnd) return;

        // Relleno
        Path fill = new Path();
        fill.moveTo(PL + iStart, cy);
        for (int i = iStart; i <= iEnd; i++) {
            float rad = (float) (i * 2.0 * Math.PI / dw);
            float y = cy - amp * (float) Math.sin(rad);
            fill.lineTo(PL + i, y);
        }
        fill.lineTo(PL + iEnd, cy);
        fill.close();
        canvas.drawPath(fill, fillPaint);

        // Trazo verde
        Path stroke = new Path();
        boolean first = true;
        for (int i = iStart; i <= iEnd; i++) {
            float rad = (float) (i * 2.0 * Math.PI / dw);
            float y = cy - amp * (float) Math.sin(rad);
            if (first) { stroke.moveTo(PL + i, y); first = false; }
            else stroke.lineTo(PL + i, y);
        }
        canvas.drawPath(stroke, greenPaint);
    }
}
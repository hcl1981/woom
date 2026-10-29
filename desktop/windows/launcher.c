/*
 * Woom.exe: starts Woom with the Java runtime that ships next to it, without a console window.
 * Layout: Woom.exe, app\*.jar, runtime\bin\javaw.exe. Arguments (e.g. --hidden) are passed on.
 */
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, LPWSTR args, int show) {
    wchar_t exe[MAX_PATH], dir[MAX_PATH], line[4 * MAX_PATH + 512];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    wcscpy(dir, exe);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;
    /* woom.launcher tells the app which program to register for "start at login". */
    _snwprintf(line, sizeof(line) / sizeof(line[0]),
        L"\"%ls\\runtime\\bin\\javaw.exe\" -Dwoom.launcher=\"%ls\" -cp \"%ls\\app\\*\" app.woom.MainKt %ls",
        dir, exe, dir, args);
    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, line, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Woom could not start: the folder \"runtime\" is missing or damaged.", L"Woom", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}

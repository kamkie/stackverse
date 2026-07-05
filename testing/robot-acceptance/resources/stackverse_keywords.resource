*** Settings ***
Documentation       Domain keywords for the Stackverse Robot Framework showcase.
Library             SeleniumLibrary    timeout=%{ROBOT_TIMEOUT=15 seconds}    run_on_failure=Capture Page Screenshot


*** Variables ***
${STACKVERSE_URL}       %{STACKVERSE_URL=http://localhost:8000}
${ROBOT_BROWSER}        %{ROBOT_BROWSER=headlesschrome}
${WINDOW_WIDTH}         1440
${WINDOW_HEIGHT}        1100


*** Keywords ***
Prepare Robot Acceptance Suite
    ${normalized}=    Evaluate    r"""${STACKVERSE_URL}""".rstrip("/")
    Set Suite Variable    ${STACKVERSE_URL}    ${normalized}
    Ensure Stackverse Is Reachable

Ensure Stackverse Is Reachable
    ${probe_url}=    Set Variable    ${STACKVERSE_URL}/
    ${status}    ${message}=    Run Keyword And Ignore Error
    ...    Evaluate
    ...    __import__("urllib.request", fromlist=["urlopen"]).urlopen(r"""${probe_url}""", timeout=5).close()
    IF    '${status}' != 'PASS'
        Fail    Stackverse is not reachable at ${STACKVERSE_URL}. Start a full stack or set STACKVERSE_URL.
    END

Open Stackverse Browser
    ${url}=    Stackverse Url For Path    /feed
    Open Browser    ${url}    ${ROBOT_BROWSER}
    Set Window Size    ${WINDOW_WIDTH}    ${WINDOW_HEIGHT}
    Wait Until Page Contains Element    css:.sv-page-title

Close Stackverse Browser
    Close All Browsers

Go To Stackverse Path
    [Arguments]    ${path}
    ${url}=    Stackverse Url For Path    ${path}
    Go To    ${url}
    Wait Until Page Contains Element    css:.sv-page-title

Stackverse Url For Path
    [Arguments]    ${path}
    ${url}=    Set Variable    ${STACKVERSE_URL}${path}
    RETURN    ${url}

Unique Marker
    ${marker}=    Evaluate    f"{int(time.time() * 1000):x}{random.randrange(16**6):06x}"    modules=time,random
    RETURN    ${marker}

Anonymous Visitor Sees Login
    Wait Until Page Contains Element    xpath://header//a[normalize-space(.)="Log in" and @href="/auth/login"]
    Page Should Not Contain Element    css:.sv-username

Login As Stackverse User
    [Arguments]    ${username}
    Go To    ${STACKVERSE_URL}/auth/login
    Wait Until Page Contains Element    id:username
    Input Text    id:username    ${username}
    Input Password    id:password    ${username}
    Click Button    id:kc-login
    Signed In User Should Be    ${username}

Signed In User Should Be
    [Arguments]    ${username}
    Wait Until Element Contains    css:.sv-username    ${username}

Log Out Of Stackverse
    Click Button    xpath://button[normalize-space(.)="Log out"]
    Wait Until Keyword Succeeds    10x    1s    Location Should Contain    /feed
    Anonymous Visitor Sees Login

Create Bookmark
    [Arguments]    ${url}    ${title}    ${notes}=${EMPTY}    ${tags}=${EMPTY}    ${visibility}=private
    Click Button    xpath://button[normalize-space(.)="Add"]
    Wait Until Page Contains Element    css:.sv-dialog
    Input Dialog Field    URL    ${url}
    Input Dialog Field    Title    ${title}
    IF    $notes != ""
        Input Dialog Field    Notes    ${notes}
    END
    IF    $tags != ""
        Input Dialog Field    Tags    ${tags}
    END
    Select Dialog Field By Value    Visibility    ${visibility}
    Click Dialog Button    Save
    Wait Until Element Is Not Visible    css:.sv-dialog
    Bookmark Should Be Visible    ${title}

Edit Bookmark Title
    [Arguments]    ${current_title}    ${next_title}
    Click Bookmark Button    ${current_title}    Edit
    Wait Until Page Contains Element    css:.sv-dialog
    Input Dialog Field    Title    ${next_title}
    Click Dialog Button    Save
    Wait Until Element Is Not Visible    css:.sv-dialog
    Bookmark Should Be Visible    ${next_title}

Delete Bookmark
    [Arguments]    ${title}
    Click Bookmark Button    ${title}    Delete
    Wait Until Page Contains Element    css:.sv-dialog
    Click Dialog Button    Delete
    Wait Until Element Is Not Visible    css:.sv-dialog
    Bookmark Should Not Be Visible    ${title}

Report Public Bookmark
    [Arguments]    ${title}    ${comment}    ${reason}=spam
    Click Bookmark Button    ${title}    Report
    Wait Until Page Contains Element    css:.sv-dialog
    Select Dialog Field By Value    Reason    ${reason}
    Input Dialog Field    Comment    ${comment}
    Click Dialog Button    Report
    Wait Until Element Is Not Visible    css:.sv-dialog
    Wait Until Page Contains Element    xpath://*[@role="status" and contains(normalize-space(.), "Report submitted")]
    Bookmark Should Be Reported    ${title}

Bookmark Should Be Reported
    [Arguments]    ${title}
    ${reported}=    Bookmark Button Locator    ${title}    Reported
    Wait Until Page Contains Element    ${reported}
    Element Should Be Disabled    ${reported}

Action Open Report
    [Arguments]    ${comment}
    Click Report Row Button    ${comment}    Action
    Report Row Should Not Be Visible    ${comment}

Admin Backoffice Should Show Full Navigation
    Go To Stackverse Path    /admin
    FOR    ${link}    IN    Dashboard    Reports    Users    Audit log    Messages
        Wait Until Page Contains Element    xpath://nav[@aria-label="Admin"]//a[normalize-space(.)="${link}"]
    END

Public Feed Should Not Show Bookmark
    [Arguments]    ${title}
    Go To Stackverse Path    /feed
    Bookmark Should Not Be Visible    ${title}

Input Dialog Field
    [Arguments]    ${label}    ${value}
    Input Text    xpath://*[contains(concat(" ", normalize-space(@class), " "), " sv-dialog ")]//label[normalize-space(.)="${label}"]/following::*[self::input or self::textarea][1]    ${value}

Select Dialog Field By Value
    [Arguments]    ${label}    ${value}
    Select From List By Value    xpath://*[contains(concat(" ", normalize-space(@class), " "), " sv-dialog ")]//label[normalize-space(.)="${label}"]/following::select[1]    ${value}

Click Dialog Button
    [Arguments]    ${label}
    Click Button    xpath://*[contains(concat(" ", normalize-space(@class), " "), " sv-dialog ")]//button[normalize-space(.)="${label}" or @aria-label="${label}"]

Bookmark Should Be Visible
    [Arguments]    ${title}
    ${locator}=    Bookmark Card Locator    ${title}
    Wait Until Page Contains Element    ${locator}

Bookmark Should Not Be Visible
    [Arguments]    ${title}
    ${locator}=    Bookmark Card Locator    ${title}
    Wait Until Page Does Not Contain Element    ${locator}

Click Bookmark Button
    [Arguments]    ${title}    ${label}
    ${locator}=    Bookmark Button Locator    ${title}    ${label}
    Click Button    ${locator}

Click Report Row Button
    [Arguments]    ${comment}    ${label}
    Click Button    xpath://tr[contains(normalize-space(.), "${comment}")]//button[normalize-space(.)="${label}" or @aria-label="${label}"]

Report Row Should Not Be Visible
    [Arguments]    ${comment}
    Wait Until Page Does Not Contain Element    xpath://tr[contains(normalize-space(.), "${comment}")]

Bookmark Card Locator
    [Arguments]    ${title}
    ${locator}=    Set Variable    xpath://*[contains(concat(" ", normalize-space(@class), " "), " sv-bookmark ") and contains(normalize-space(.), "${title}")]
    RETURN    ${locator}

Bookmark Button Locator
    [Arguments]    ${title}    ${label}
    ${locator}=    Set Variable    xpath://*[contains(concat(" ", normalize-space(@class), " "), " sv-bookmark ") and contains(normalize-space(.), "${title}")]//button[normalize-space(.)="${label}" or @aria-label="${label}"]
    RETURN    ${locator}

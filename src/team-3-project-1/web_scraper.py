"""Scrape Panda Express nutrition rows into results.csv using Selenium."""

import csv

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC


def scrapeWebsite(url):
    """Load nutrition rows from the URL and save their cells as CSV data."""
    chromeOptions = Options()
    # chromeOptions.add_argument("--headless")
    chromeOptions.add_argument("--no-sandbox")
    chromeOptions.add_argument("--disable-dev-shm-usage")

    driver = webdriver.Chrome(options=chromeOptions)
    driver.get(url)

    try:
        # Wait up to 15 seconds for nutrition rows to appear
        WebDriverWait(driver, 15).until(
            EC.presence_of_all_elements_located(
                (By.CSS_SELECTOR, "tr.row-nutrition")
            )
        )
    except:
        print("Could not find nutrition rows.")
        print(driver.title)
        driver.quit()
        return

    rows = driver.find_elements(By.CSS_SELECTOR, "tr.row-nutrition")

    print("Rows found:", len(rows))

    scrapedData = []

    for row in rows:
        cells = row.find_elements(By.TAG_NAME, "td")

        if len(cells) == 0:
            continue

        values = [cell.text.strip() for cell in cells]

        print(values)

        scrapedData.append(values)

    driver.quit()

    with open("results.csv", "w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file)

        writer.writerow([
            "Menu Item",
            "Portion",
            "Calories",
            "Calories From Fat",
            "Total Fat",
            "Saturated Fat",
            "Trans Fat",
            "Cholesterol",
            "Sodium",
            "Total Carbs",
            "Dietary Fiber",
            "Sugars",
            "Protein"
        ])

        writer.writerows(scrapedData)


if __name__ == "__main__":
    targetUrl = "https://www.pandaexpress.com/nutritioninformation"
    scrapeWebsite(targetUrl)
